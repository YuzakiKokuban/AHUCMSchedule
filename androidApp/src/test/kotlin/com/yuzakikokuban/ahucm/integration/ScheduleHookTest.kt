package com.yuzakikokuban.ahucm.integration

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Executable
import java.lang.reflect.Proxy

class ScheduleHookTest {
    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T

    private class Registration(val executable: Executable, val hooker: XposedInterface.Hooker)
    private fun module(registrations: MutableList<Registration>): XposedModule {
        assertTrue("Entry must use modern XposedModule", XposedModule::class.java.isAssignableFrom(ScheduleHook::class.java))
        val module = ScheduleHook::class.java.getConstructor().newInstance() as XposedModule
        // Simulates framework attachment, never invoked by production module code.
        val framework = proxy<XposedInterface> { name, args ->
            when (name) {
                "hook" -> {
                    val executable = args[0] as Executable
                    lateinit var builder: XposedInterface.HookBuilder
                    builder = proxy { method, values ->
                        if (method == "intercept") {
                            registrations += Registration(executable, values[0] as XposedInterface.Hooker)
                            proxy<XposedInterface.HookHandle> { _, _ -> null }
                        } else builder
                    }
                    builder
                }
                else -> null
            }
        }
        module.attachFramework(framework, Runnable {})
        return module
    }

    private fun ready(name: String, loader: ClassLoader = javaClass.classLoader!!) =
        proxy<XposedModuleInterface.PackageReadyParam> { method, _ ->
            when (method) {
                "getPackageName" -> name
                "getClassLoader" -> loader
                "isFirstPackage" -> true
                else -> null
            }
        }

    @Test fun registersOnlyXiaoAiConstructorAndDoesNotDuplicateIt() {
        val registrations = mutableListOf<Registration>()
        val module = module(registrations)
        module.onPackageReady(ready("com.example.other"))
        assertTrue(registrations.isEmpty())
        repeat(2) { module.onPackageReady(ready("com.miui.voiceassist")) }
        assertEquals(2, registrations.size)
        val constructor = registrations.single { it.executable is java.lang.reflect.Constructor<*> }
        assertEquals("com.xiaomi.voiceassistant.commonweb.CommonWebView", constructor.executable.declaringClass.name)
        assertArrayEquals(arrayOf(Context::class.java), constructor.executable.parameterTypes)
        assertTrue(registrations.any { it.executable.name == "call" })
    }

    @Test fun constructorHookPreservesHostResultAndCallsOriginalExactlyOnce() {
        val registrations = mutableListOf<Registration>()
        module(registrations).onPackageReady(ready("com.miui.voiceassist"))
        var calls = 0
        val marker = Any()
        val chain = proxy<XposedInterface.Chain> { method, _ ->
            if (method == "proceed") { calls++; marker } else null
        }
        assertSame(marker, registrations.single { it.executable is java.lang.reflect.Constructor<*> }.hooker.intercept(chain))
        assertEquals(1, calls)
    }

    @Test fun originalConstructorFailureIsNotSwallowedOrRetried() {
        val registrations = mutableListOf<Registration>()
        module(registrations).onPackageReady(ready("com.miui.voiceassist"))
        val failure = IllegalStateException("host constructor failed")
        var calls = 0
        val chain = proxy<XposedInterface.Chain> { method, _ ->
            if (method == "proceed") { calls++; throw failure } else null
        }
        try { registrations.single { it.executable is java.lang.reflect.Constructor<*> }.hooker.intercept(chain); fail("Host failure must propagate") }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals(1, calls)
    }

    @Test fun unsupportedHostFailsClosedAndCanRetryWhenLoaderIsReady() {
        val registrations = mutableListOf<Registration>()
        val module = module(registrations)
        val missingLoader = object : ClassLoader(null) {}
        module.onPackageReady(ready("com.miui.voiceassist", missingLoader))
        assertTrue(registrations.isEmpty())
        module.onPackageReady(ready("com.miui.voiceassist"))
        assertEquals(2, registrations.size)
    }

    @Test fun liveReloadIsRejectedWhileWebViewWorkersMayStillBeRunning() {
        val module = module(mutableListOf())
        assertFalse(module.onHotReloading(proxy { _, _ -> null }))
    }
}
