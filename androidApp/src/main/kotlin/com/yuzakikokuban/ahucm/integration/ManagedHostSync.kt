package com.yuzakikokuban.ahucm.integration

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Top-frame evaluateJavascript only: no exposed JavascriptInterface for sync. */
object ManagedHostSync {
    private val running = WeakHashMap<WebView, Boolean>()
    private var source: String? = null
    private fun trusted(url: String?): Boolean = runCatching {
        val uri = Uri.parse(url)
        uri.scheme == "https" && uri.host == "i.ai.mi.com" && uri.path?.startsWith("/h5/precache/ai-schedule/") == true
    }.getOrDefault(false)

    fun attach(view: WebView) {
        if (running.put(view, true) == true) return
        Log.i("AhuHostSync", "Host WebView attached")
        val weak = WeakReference(view)
        var token: String? = null
        var active = false
        var busy = false
        var lastReportedSeq = -1
        val loop = object : Runnable {
            override fun run() {
                val web = weak.get() ?: return
                web.postDelayed(this, if (active) 500 else 5_000)
                if (busy || !web.isAttachedToWindow || !trusted(web.url)) return
                if (!active && web.progress < 100) return
                busy = true
                if (!active) {
                    web.evaluateJavascript("document.readyState === 'complete' && typeof window.webpackJsonp !== 'undefined'") { ready ->
                    if (ready != "true" || !trusted(web.url) || web.progress < 100) {
                        busy = false
                        return@evaluateJavascript
                    }
                    try {
                        val b = web.context.contentResolver.call(ManagedSyncProvider.URI, "host_take", null, null)
                        val job = b?.getString("job")
                        if (job == null) { busy = false; return@evaluateJavascript }
                        Log.i("AhuHostSync", "Sync job claimed")
                        val parsed = JSONObject(job)
                        token = parsed.getString("token")
                        if (source == null) source = web.context.contentResolver.call(ManagedSyncProvider.URI, "host_script", null, null)?.getString("script")
                        check(!source.isNullOrBlank()) { "sync_script_unavailable" }
                        val bootstrap = """
                            (function(){
                              if(window.__ahuSyncJob) return;
                              const job=$job;
                              const box=window.__ahuSyncJob={token:job.token,report:null,ack:0,denied:0,cancelRequested:job.cancelRequested===true,seq:0,final:false};
                              const checkpoint=async r=>{
                                const seq=++box.seq;box.report=r;
                                const deadline=Date.now()+30000;
                                while(box.ack!==seq&&box.denied!==seq){if(Date.now()>deadline)throw Error('native_checkpoint_timeout');await new Promise(r=>setTimeout(r,100));}
                                if(box.denied===seq)return {allowMutation:false,cancelRequested:box.cancelRequested};
                                return true;
                              };
                              (async()=>{
                                try{
                                  if(!Array.from(document.scripts).some(s=>s.src.endsWith('/umi.777d5973.js')))throw Error('unsupported_host_bundle');
                                  let req;
                                  const id='ahu_'+Date.now();
                                  window.webpackJsonp.push([[id],{[id]:function(m,e,r){req=r;}},[[id]]]);
                                  const request=req('sS9P').a;
                                  if(typeof request!=='function')throw Error('host_request_unavailable');
                                  const readyBy=Date.now()+20000;
                                  while(!req('Q2AE').a.getState().multiTable.currentTable.id){
                                    if(Date.now()>readyBy)throw Error('host_not_initialized');
                                    await new Promise(r=>setTimeout(r,200));
                                  }
                                  const result=await AhuManagedSync.run(job,request,checkpoint);
                                  if(result.state==='applied'&&!result.cancelRequested){
                                    try{req('uJMD').j();}catch(e){}
                                    try{req('Q2AE').a.dispatch.multiTable.initTables();}catch(e){}
                                  }
                                  box.report=result;
                                }catch(e){box.report={state:'pending',phase:job.phase,tableId:job.tableId,revision:job.revision,message:'host_adapter_unavailable'};}
                                box.seq++;box.final=true;
                              })();
                            })();
                        """.trimIndent()
                        active = true
                        web.evaluateJavascript(source + "\n" + bootstrap) {
                            Log.i("AhuHostSync", "Driver evaluation returned")
                            busy = false
                        }
                    } catch (e: Exception) {
                        Log.w("AhuHostSync", "Driver bootstrap failed: ${e.javaClass.simpleName}")
                        busy = false
                    }
                    }
                } else {
                    web.evaluateJavascript("JSON.stringify(window.__ahuSyncJob ? {token:window.__ahuSyncJob.token,report:window.__ahuSyncJob.report,seq:window.__ahuSyncJob.seq,final:window.__ahuSyncJob.final}:null)") { raw ->
                        try {
                            if (!trusted(web.url)) return@evaluateJavascript
                            val encoded = JSONArray("[$raw]").optString(0)
                            if (encoded == "null" || encoded.isBlank()) { active = false; return@evaluateJavascript }
                            val snapshot = JSONObject(encoded)
                            check(snapshot.getString("token") == token)
                            if (snapshot.isNull("report")) return@evaluateJavascript
                            val receipt = snapshot.getJSONObject("report")
                            if (snapshot.getInt("seq") != lastReportedSeq) {
                                lastReportedSeq = snapshot.getInt("seq")
                                Log.i("AhuHostSync", "Receipt phase=${receipt.optString("phase")} state=${receipt.optString("state")}")
                            }
                            val isFinal = snapshot.getBoolean("final")
                            val extras = Bundle().apply {
                                putString("token", token)
                                putString("receipt", receipt.toString())
                                putBoolean("final", isFinal)
                            }
                            val saved = web.context.contentResolver.call(ManagedSyncProvider.URI, "host_receipt", null, extras)
                            check(saved != null)
                            if (isFinal) {
                                web.evaluateJavascript("delete window.__ahuSyncJob", null)
                                active = false
                            } else {
                                val seq = snapshot.getInt("seq")
                                val decision = if (saved.getBoolean("allowMutation")) "window.__ahuSyncJob.ack=$seq;"
                                    else "window.__ahuSyncJob.cancelRequested=true;window.__ahuSyncJob.denied=$seq;"
                                web.evaluateJavascript("if(window.__ahuSyncJob && window.__ahuSyncJob.token===${JSONObject.quote(token)}){window.__ahuSyncJob.report=null;$decision}", null)
                            }
                        } catch (e: Exception) {
                            Log.w("AhuHostSync", "Receipt transport failed: ${e.javaClass.simpleName}")
                            // No ack means JS cannot issue the next mutation. The durable
                            // lease expires into readback-only recovery after interruption.
                        } finally { busy = false }
                    }
                }
            }
        }
        view.post(loop)
    }
}
