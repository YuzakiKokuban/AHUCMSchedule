package com.yuzakikokuban.ahucm.integration

import android.content.Context
import android.content.ContentProvider
import android.os.Binder
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/** Modern API entry loaded inside XiaoAi only; the API is supplied by the framework. */
class ScheduleHook : XposedModule() {
    private var installed = false
    private var backgroundInstalled = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "Modern Xposed API $apiVersion loaded")
    }

    @Synchronized override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != "com.miui.voiceassist") return
        if (!backgroundInstalled) try {
            val providerClass = Class.forName("com.xiaomi.SpeechProvider.search.VaSettingsSearchProvider", false, param.classLoader)
            val call = providerClass.getMethod("call", String::class.java, String::class.java, Bundle::class.java)
            hook(call).setId("ahucm.background-sync").setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                if (!providerClass.isInstance(chain.thisObject) || chain.getArg(0) != ManagedNativeSync.METHOD) chain.proceed()
                else {
                    val context = requireNotNull((chain.thisObject as ContentProvider).context)
                    check(context.packageManager.getPackagesForUid(Binder.getCallingUid())?.contains("com.yuzakikokuban.ahucm") == true) { "unauthorized_sync_wake" }
                    ManagedNativeSync.wake(context, param.classLoader)
                    Bundle().apply { putInt("schema", 1); putBoolean("background", true) }
                }
            }
            backgroundInstalled = true
            log(Log.INFO, TAG, "Background sync hook installed")
        } catch (_: Throwable) {
            log(Log.WARN, TAG, "Background sync hook unavailable")
        }
        if (!installed) try {
            // PackageReady supplies the final host loader, including AppComponentFactory changes.
            val viewClass = Class.forName(
                "com.xiaomi.voiceassistant.commonweb.CommonWebView", false, param.classLoader
            ).asSubclass(WebView::class.java)
            val constructor = viewClass.getDeclaredConstructor(Context::class.java)
            hook(constructor)
                .setId("ahucm.common-web-view")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    // Never swallow or repeat an exception from the original host constructor.
                    val result = chain.proceed()
                    try { (chain.thisObject as? WebView)?.let(ManagedHostSync::attach) }
                    catch (_: Throwable) { /* Keep the host usable; the queue remains pending. */ }
                    result
                }
            installed = true
            log(Log.INFO, TAG, "XiaoAi WebView hook installed")
        } catch (_: Throwable) {
            log(Log.WARN, TAG, "XiaoAi WebView hook unavailable; sync remains pending")
        }
    }

    // A WebView can hold our polling/evaluation callbacks. Retiring that classloader while
    // a write is in flight is unsafe; require a host process restart for module updates.
    override fun onHotReloading(param: HotReloadingParam): Boolean = false

    private companion object { const val TAG = "AhuScheduleHook" }
}
