package com.yuzakikokuban.ahucm.integration

import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ProviderInfo
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Exercises the real provider journal without touching the user's sync queue. */
class ManagedSyncResumeTest {
    private class Signals(var background: Int = 0, val activities: MutableList<Intent> = mutableListOf())
    private fun withProvider(test: (ManagedSyncProvider, File, JSONObject, Signals) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "sync-resume-").toFile()
        val signals = Signals()
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = directory
            override fun startActivity(intent: Intent) { signals.activities += intent }
        }
        val payload = JSONObject().put("schema", 1).put("source", "com.yuzakikokuban.ahucm")
            .put("owner", "a".repeat(64)).put("revision", "b".repeat(64)).put("termCode", "202601")
            .put("termLabel", "Test term").put("retrievedAt", "2026-10-25T00:00:00Z").put("weekOneMonday", "2026-08-31")
            .put("timer", JSONObject().put("totalWeek", 20).put("startSemester", 1788105600000L)
                .put("startWithSunday", false).put("showWeekend", true).put("forenoon", 12).put("afternoon", 2).put("night", 0)
                .put("sections", JSONArray().apply {
                    for (i in 1..14) put(JSONObject().put("section", i)
                        .put("startTime", "%02d:00".format(i)).put("endTime", "%02d:30".format(i)))
                }))
            .put("courses", JSONArray().put(JSONObject().put("name", "Test course").put("teacher", "").put("position", "")
                .put("day", 1).put("sections", JSONArray().put(1)).put("weeks", JSONArray().put(1))))
        val provider = ManagedSyncProvider { signals.background++ }
        provider.attachInfo(isolated, ProviderInfo().apply { authority = ManagedSyncProvider.AUTHORITY })
        try { test(provider, File(directory, "managed-schedule-sync.json"), payload, signals) }
        finally { provider.shutdown(); directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    private fun journal(payload: JSONObject, tableId: String = "10", phase: String = "verifying", lease: Long = 1) =
        JSONObject().put("payload", payload).put("owner", payload.getString("owner"))
            .put("revision", payload.getString("revision")).put("termCode", payload.getString("termCode"))
            .put("bindings", JSONObject().put(payload.getString("owner") + ":202601", tableId))
            .put("tableId", tableId).put("state", "pending").put("phase", phase)
            .put("cancelRequested", true).put("token", "expired-lease").put("leaseUntil", lease)

    private fun apply(provider: ManagedSyncProvider, payload: JSONObject, resume: Boolean = true) =
        provider.call("apply", null, Bundle().apply { putString("payload", payload.toString()); putBoolean("resume", resume) })

    @Test fun expiredCanceledLeaseCanResumeWithTheSameOrANewRevision() = withProvider { provider, file, payload, _ ->
        for (newRevision in listOf(false, true)) {
            file.writeText(journal(payload).toString())
            val next = JSONObject(payload.toString()).apply { if (newRevision) put("revision", "c".repeat(64)) }
            val reply = apply(provider, next)
            assertEquals("accepted", reply.getString("state"))
            assertEquals(next.getString("revision"), reply.getString("revision"))
            assertEquals("10", reply.getString("tableId"))
            val saved = JSONObject(file.readText())
            assertFalse(saved.getBoolean("cancelRequested"))
            assertEquals("", saved.optString("token"))
            assertEquals("10", saved.getJSONObject("bindings").getString(payload.getString("owner") + ":202601"))
        }
    }

    @Test fun automaticSyncCannotClearCancellation() = withProvider { provider, file, payload, _ ->
        val original = journal(payload).toString()
        file.writeText(original)
        assertEquals("management_canceled", runCatching { apply(provider, payload, false) }.exceptionOrNull()?.message)
        assertEquals(original, file.readText())
    }

    @Test fun liveLeaseAndAmbiguousCreationRemainBlocked() = withProvider { provider, file, payload, _ ->
        for (record in listOf(journal(payload, lease = System.currentTimeMillis() + 60_000), journal(payload, "", "table-creating"))) {
            val original = record.toString()
            file.writeText(original)
            assertEquals("canceled_write_unresolved", runCatching { apply(provider, payload) }.exceptionOrNull()?.message)
            assertEquals(original, file.readText())
        }
    }

    @Test fun automaticAndManualSyncBothQueueWithoutOpeningAnyActivity() = withProvider { provider, file, payload, signals ->
        apply(provider, payload, false)
        assertTrue(signals.activities.isEmpty())
        assertEquals("accepted", JSONObject(file.readText()).getString("state"))
        file.writeText(JSONObject(file.readText()).put("state", "applied").put("phase", "verified").toString())
        apply(provider, payload, false)
        assertTrue(signals.activities.isEmpty())
        assertEquals("accepted", JSONObject(file.readText()).getString("state"))
        apply(provider, payload, true)
        assertTrue(signals.activities.isEmpty())
        assertEquals(3, signals.background)
    }

    @Test fun newestPayloadWaitsForTheCurrentClaimWithoutLosingEitherIdentity() = withProvider { provider, file, payload, signals ->
        val original = journal(payload, lease = System.currentTimeMillis() + 60_000).put("cancelRequested", false)
        file.writeText(original.toString())
        for (revision in listOf("c", "d")) {
            val next = JSONObject(payload.toString()).put("revision", revision.repeat(64))
            val reply = apply(provider, next, false)
            assertEquals(revision.repeat(64), reply.getString("revision"))
            val saved = JSONObject(file.readText())
            assertEquals(payload.getString("revision"), saved.getString("revision"))
            assertEquals("expired-lease", saved.getString("token"))
            assertEquals(revision.repeat(64), saved.getJSONObject("nextPayload").getString("revision"))
        }
        provider.call("cancel", null, Bundle().apply { putString("owner", payload.getString("owner")); putString("termCode", "202601") })
        assertFalse(JSONObject(file.readText()).has("nextPayload"))
        assertEquals(2, signals.background)
    }
}
