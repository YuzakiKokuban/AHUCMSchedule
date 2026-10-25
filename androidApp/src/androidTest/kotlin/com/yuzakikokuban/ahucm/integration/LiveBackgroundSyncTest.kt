package com.yuzakikokuban.ahucm.integration

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Explicit opt-in only. Always restores the real managed timetable in finally. */
class LiveBackgroundSyncTest {
    @Test fun consecutiveTimetableChangesSyncWithoutOpeningXiaoAiAndRestoreOriginal() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveXiaoAi") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Keep only our own app foreground on devices with aggressive freezer
        // tools. AboutActivity does not start another school refresh during test.
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND")
        try {
            context.startActivity(android.content.Intent(context, com.yuzakikokuban.ahucm.update.AboutActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        val resolver = context.contentResolver
        val journal = File(context.filesDir, "managed-schedule-sync.json")
        assertTrue("Enable XiaoAi management before opting into this live test", journal.isFile)
        checkNotNull(resolver.acquireContentProviderClient(ManagedNativeSync.HOST_URI)).use { host ->
        assertTrue(host.call(ManagedNativeSync.METHOD, null, null)?.getBoolean("background") == true)
        var initial = JSONObject(journal.readText())
        val baselineDeadline = System.currentTimeMillis() + 90_000
        var nextPoke = 0L
        while (initial.optString("state") != "applied" && !initial.optBoolean("cancelRequested") && System.currentTimeMillis() < baselineDeadline) {
            if (System.currentTimeMillis() >= nextPoke) { host.call(ManagedNativeSync.METHOD, null, null); nextPoke = System.currentTimeMillis() + 1000 }
            Thread.sleep(250)
            initial = JSONObject(journal.readText())
        }
        assertFalse("Live test must not resume disabled management", initial.optBoolean("cancelRequested"))
        assertEquals("Baseline sync did not settle", "applied", initial.optString("state"))
        val original = initial.getJSONObject("payload")
        val tableId = initial.getString("tableId")
        fun submit(payload: JSONObject) = checkNotNull(resolver.call(ManagedSyncProvider.URI, "apply", null, Bundle().apply {
            putString("payload", payload.toString()); putBoolean("resume", false)
        }))
        fun awaitVerified(revision: String) {
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline) {
                val status = resolver.call(ManagedSyncProvider.URI, "status", null, null)
                if (status?.getString("revision") == revision && status.getString("state") == "applied") {
                    assertEquals("verified", status.getString("phase"))
                    assertEquals("server_readback_verified", status.getString("message"))
                    assertEquals(tableId, status.getString("tableId"))
                    assertNotEquals("com.miui.voiceassist", instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
                    return
                }
                Thread.sleep(250)
            }
            fail("Background sync did not produce a verified receipt within 90 seconds")
        }
        try {
            val changed = listOf("A", "B").map { marker ->
                JSONObject(original.toString()).apply {
                    getJSONArray("courses").getJSONObject(0).put("position", "后台同步隔离验证$marker")
                    put("revision", MessageDigest.getInstance("SHA-256").digest(toString().toByteArray())
                        .joinToString("") { "%02x".format(it) })
                }
            }
            submit(changed[0])
            assertEquals(changed[1].getString("revision"), submit(changed[1]).getString("revision"))
            awaitVerified(changed[1].getString("revision"))
        } finally {
            submit(original)
            awaitVerified(original.getString("revision"))
        }
        }
    }
}
