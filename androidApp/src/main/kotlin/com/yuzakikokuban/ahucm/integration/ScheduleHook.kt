package com.yuzakikokuban.ahucm.integration

import android.content.Context
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

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "Modern Xposed API $apiVersion loaded")
    }

    @Synchronized override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != "com.miui.voiceassist" || installed) return
        try {
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
