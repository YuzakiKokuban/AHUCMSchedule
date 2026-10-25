package com.yuzakikokuban.ahucm.integration

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Own bundled code, no remote page, no Activity, and no authentication exposed to JS. */
internal object ManagedNativeSync {
    const val METHOD = "ahucm_managed_sync_v1"
    val HOST_URI: Uri = Uri.parse("content://com.xiaomi.SpeechProvider.search.VaSettingsSearchProvider")
    private val control = Executors.newCachedThreadPool()
    private val requests = Executors.newCachedThreadPool()
    private val timers = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val gate = BackgroundRunGate(timers)

    fun wake(context: Context, loader: ClassLoader) {
        val app = context.applicationContext
        control.execute {
            val ticket = gate.acquire {
                Log.w("AhuNativeSync", "Background startup timed out")
                timers.schedule({ wake(app, loader) }, 1, TimeUnit.SECONDS)
            } ?: return@execute
            var claimed: JSONObject? = null
            try {
                val resolver = app.contentResolver
                val raw = resolver.call(ManagedSyncProvider.URI, "host_take", null, null)?.getString("job")
                if (!gate.owns(ticket)) return@execute
                if (raw == null) { gate.release(ticket); return@execute }
                val job = JSONObject(raw)
                claimed = job
                val script = checkNotNull(resolver.call(ManagedSyncProvider.URI, "host_script", null, null)?.getString("script"))
                if (!gate.owns(ticket)) return@execute
                val session = Session(app, loader, job, ticket)
                if (!gate.started(ticket)) { session.retire(); return@execute }
                main.post { session.launch(script) }
            } catch (e: Exception) {
                gate.release(ticket)
                claimed?.let { job -> runCatching {
                    val phase = job.optString("phase")
                    val receipt = JSONObject().put("state", if (phase == "accepted") "failed" else "pending")
                        .put("phase", phase).put("tableId", job.optString("tableId"))
                        .put("revision", job.getString("revision")).put("message", "background_host_unavailable")
                    app.contentResolver.call(ManagedSyncProvider.URI, "host_receipt", null, Bundle().apply {
                        putString("token", job.getString("token")); putString("receipt", receipt.toString()); putBoolean("final", true)
                    })
                } }
                Log.w("AhuNativeSync", "Background startup failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private class Session(private val context: Context, loader: ClassLoader, private val job: JSONObject, private val ticket: BackgroundRunGate.Ticket) {
        private val resolver = context.contentResolver
        private val token = job.getString("token")
        private val request = NativeHostRequest(loader)
        private var web: WebView? = null
        @Volatile private var lastReceipt = JSONObject().put("state", "pending").put("phase", job.optString("phase"))
            .put("tableId", job.optString("tableId")).put("revision", job.getString("revision")).toString()
        private val lifetime = BackgroundSessionLifetime(timers, control, {
            resolver.call(ManagedSyncProvider.URI, "host_heartbeat", null, Bundle().apply { putString("token", token) })
        }, ::fail)

        @Suppress("SetJavaScriptEnabled")
        fun launch(script: String) {
            if (lifetime.finished) return
            try {
                val view = WebView(context)
                web = view
                view.settings.javaScriptEnabled = true
                view.settings.blockNetworkLoads = true
                view.addJavascriptInterface(this, "AhuNative")
                val started = AtomicBoolean(false)
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        if (!started.compareAndSet(false, true) || lifetime.finished) return
                        val bootstrap = """
                            (function(){
                              let seq=0;const callbacks=new Map();
                              window.__ahuNative={receive:(id,body)=>{const cb=callbacks.get(id);if(cb){callbacks.delete(id);cb(JSON.parse(body));}}};
                              const request=q=>new Promise(resolve=>{const id=String(++seq);callbacks.set(id,resolve);AhuNative.request(id,JSON.stringify(q));});
                              const checkpoint=async r=>JSON.parse(AhuNative.checkpoint(JSON.stringify(r)));
                              AhuManagedSync.run(${job},request,checkpoint).then(r=>AhuNative.complete(JSON.stringify(r))).catch(()=>AhuNative.failed());
                            })();
                        """.trimIndent()
                        view.evaluateJavascript(script + "\n" + bootstrap, null)
                    }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        fail("background_renderer_unavailable")
                        return true
                    }
                }
                view.loadDataWithBaseURL("https://i.ai.mi.com/ahucm-native/", "<html><head><meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; script-src 'unsafe-inline'\"></head></html>", "text/html", "UTF-8", null)
                Log.i("AhuNativeSync", "Background job started")
            } catch (_: Exception) { fail("background_renderer_unavailable") }
        }

        @JavascriptInterface fun request(id: String, raw: String) {
            requests.execute {
                if (lifetime.finished) return@execute
                val response = runCatching { request.execute(raw) }.getOrElse { "{\"code\":-1}" }
                main.post {
                    if (!lifetime.finished) web?.evaluateJavascript("window.__ahuNative.receive(${JSONObject.quote(id)},${JSONObject.quote(response)})", null)
                }
            }
        }

        @JavascriptInterface fun checkpoint(raw: String): String {
            check(!lifetime.finished) { "background_job_finished" }
            val saved = receipt(raw, false)
            lastReceipt = raw
            return JSONObject().put("allowMutation", saved.getBoolean("allowMutation"))
                .put("cancelRequested", saved.getBoolean("cancelRequested")).toString()
        }

        @JavascriptInterface fun complete(raw: String) { finish(raw) }
        @JavascriptInterface fun failed() { fail("background_driver_failed") }
        fun retire() { fail("background_startup_timeout") }

        private fun receipt(raw: String, final: Boolean): Bundle = checkNotNull(resolver.call(ManagedSyncProvider.URI, "host_receipt", null,
            Bundle().apply { putString("token", token); putString("receipt", raw); putBoolean("final", final) }))

        private fun fail(message: String) = finish(JSONObject(lastReceipt).put("state", "pending").put("message", message).toString())

        private fun finish(raw: String) {
            if (!lifetime.finish()) return
            // Timeout/render loss cleanup cannot wait behind a frozen provider
            // or HTTP request. Late callbacks are fenced by lifetime and token.
            main.post { web?.removeJavascriptInterface("AhuNative"); web?.destroy(); web = null }
            requests.execute { request.cancel() }
            gate.release(ticket)
            control.execute {
                var pending = false
                try {
                    val saved = receipt(raw, true)
                    pending = saved.getString("state") == "pending"
                    Log.i("AhuNativeSync", "Background receipt state=${saved.getString("state")} phase=${saved.getString("phase")}")
                } catch (e: Exception) { Log.w("AhuNativeSync", "Background receipt failed: ${e.javaClass.simpleName}") }
                finally {
                    // Drain a newer queued revision; pending repairs respect provider backoff.
                    timers.schedule({ wake(context, context.classLoader) }, if (pending) 61 else 0, TimeUnit.SECONDS)
                }
            }
        }
    }
}
