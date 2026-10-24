package com.yuzakikokuban.ahucm.integration

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.yuzakikokuban.ahucm.R
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Narrow Binder interface: only this app can enqueue; only the
 * system XiaoAi process can consume and acknowledge. No credential transport. */
class ManagedSyncProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.yuzakikokuban.ahucm.schedule.sync"
        val URI: Uri = Uri.parse("content://$AUTHORITY")
        private const val CLIENT = "com.yuzakikokuban.ahucm"
        private val HEX = Regex("[0-9a-f]{64}")
        private val LOCK = Any()
    }
    private val journal get() = AtomicFile(File(requireNotNull(context).filesDir, "managed-schedule-sync.json"))
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = synchronized(LOCK) {
        val host = method.startsWith("host_")
        authenticate(host)
        if (method == "host_script") return@synchronized Bundle().apply {
            putString("script", requireNotNull(context).resources.openRawResource(R.raw.managed_sync).bufferedReader().use { it.readText() })
        }
        var record = read()
        when (method) {
            "apply" -> {
                val payload = validate(extras?.getString("payload") ?: error("missing_payload"))
                if (record?.optBoolean("cancelRequested") == true) {
                    check(extras.getBoolean("resume")) { "management_canceled" }
                    check(ManagedCancellation.canResume(record.optString("state"), record.optString("token").isNotEmpty())) { "canceled_write_unresolved" }
                    record.put("cancelRequested", false)
                    if (record.optString("state") == "failed") record.put("phase", "accepted")
                    write(record)
                }
                if (record != null && record.getString("revision") == payload.getString("revision") &&
                    record.getString("owner") == payload.getString("owner") && record.getString("termCode") == payload.getString("termCode")) {
                    // A successful receipt is historical: the host may have been edited,
                    // or the current week may have advanced. Recheck the bound table.
                    if (record.optString("state") == "applied") {
                        record.put("state", "accepted").put("phase", "verifying")
                            .put("message", "queued_for_host").put("token", "")
                            .put("leaseUntil", 0).put("nextAttempt", 0)
                        write(record)
                    }
                    // A failure before the first acknowledged write may be queued
                    // again. Every interrupted write has a different durable phase
                    // and remains restricted to readback-only recovery.
                    if (record.optString("state") == "failed" && record.optString("phase") == "accepted") {
                        record.put("state", "accepted").put("message", "queued_for_host")
                            .put("token", "").put("leaseUntil", 0).put("nextAttempt", 0)
                        write(record)
                    }
                    if (record.optString("state") != "applied") wakeHost()
                    return@synchronized reply(record)
                }
                check(record == null || record.optString("state") in listOf("applied", "failed")) { "previous_revision_unresolved" }
                // The target binding belongs to exactly one school account + term.
                val bindings = record?.optJSONObject("bindings") ?: JSONObject()
                val bindingKey = payload.getString("owner") + ":" + payload.getString("termCode")
                val tableId = bindings.optString(bindingKey)
                record = JSONObject().put("payload", payload).put("owner", payload.getString("owner"))
                    .put("termCode", payload.getString("termCode")).put("revision", payload.getString("revision"))
                    .put("tableId", tableId).put("bindings", bindings).put("state", "accepted").put("phase", "accepted").put("cancelRequested", false)
                    .put("message", "queued_for_host").put("updatedAt", Instant.now().toString())
                write(record)
                wakeHost()
                reply(record)
            }
            "status" -> {
                if (record != null && ((extras?.getString("owner")?.let { it != record.optString("owner") } == true) ||
                    (extras?.getString("termCode")?.let { it != record.optString("termCode") } == true))) record = null
                reply(record)
            }
            "cancel" -> {
                val owner = extras?.getString("owner") ?: error("missing_owner")
                val termCode = extras.getString("termCode") ?: error("missing_termCode")
                if (record != null && (record.optString("owner") != owner || record.optString("termCode") != termCode)) {
                    return@synchronized reply(record).apply { putBoolean("cancelMatched", false) }
                }
                // A tombstone also closes the race with a local worker that has not
                // enqueued its first payload yet. Only explicit resume can clear it.
                if (record == null) record = JSONObject().put("owner", owner).put("termCode", termCode)
                    .put("revision", "").put("tableId", "").put("bindings", JSONObject())
                    .put("state", "accepted").put("phase", "accepted")
                val decision = ManagedCancellation.request(record.optString("state"), record.optString("phase"), record.optString("token").isNotEmpty())
                record.put("cancelRequested", true).put("state", decision.state).put("phase", decision.phase)
                    .put("message", decision.message).put("updatedAt", Instant.now().toString())
                write(record)
                reply(record).apply { putBoolean("cancelMatched", true) }
            }
            "host_take" -> {
                if (record == null || record.optString("state") in listOf("applied", "failed") ||
                    record.optLong("nextAttempt") > System.currentTimeMillis() ||
                    (record.optString("token").isNotEmpty() && record.optLong("leaseUntil") > System.currentTimeMillis())) return@synchronized Bundle.EMPTY
                record.put("token", UUID.randomUUID().toString()).put("leaseUntil", System.currentTimeMillis() + 600_000)
                write(record)
                Bundle().apply { putString("job", JSONObject(record.toString()).toString()) }
            }
            "host_receipt" -> {
                requireNotNull(record) { "missing_job" }
                check(extras?.getString("token") == record.optString("token") && record.optString("token").isNotEmpty()) { "stale_host_lease" }
                val receipt = JSONObject(extras.getString("receipt") ?: error("missing_receipt"))
                check(receipt.getString("revision") == record.getString("revision")) { "revision_mismatch" }
                val state = receipt.getString("state")
                require(state in listOf("pending", "applied", "failed"))
                val id = receipt.optString("tableId")
                require(id.isEmpty() || Regex("[1-9][0-9]*").matches(id))
                val isFinal = extras.getBoolean("final")
                check(ManagedTargetBinding.accepts(record.optString("tableId"), record.optString("phase"),
                    id, receipt.getString("phase"), state == "pending" && !isFinal)) { "target_changed" }
                val canceled = record.optBoolean("cancelRequested")
                if (canceled && !isFinal) {
                    // Keep any returned identity from a previously authorized create,
                    // but never acknowledge another mutation after cancellation.
                    if (id.isNotEmpty()) {
                        record.put("tableId", id)
                        record.getJSONObject("bindings").put(record.getString("owner") + ":" + record.getString("termCode"), id)
                    }
                    write(record)
                    return@synchronized reply(record)
                }
                if (state == "applied") {
                    check(receipt.optString("phase") == "verified" && receipt.optString("message") == "server_readback_verified")
                    check(receipt.optInt("courseCount") == record.getJSONObject("payload").getJSONArray("courses").length())
                    val colorCount = receipt.optInt("colorCount")
                    check(colorCount in 1..receipt.getInt("courseCount")) { "invalid_color_readback" }
                    record.put("colorCount", colorCount)
                }
                val terminalCanceled = canceled && state != "applied" && record.optString("phase") == "accepted"
                val savedState = if (terminalCanceled) "failed" else if (canceled && state != "applied") "pending" else state
                val savedPhase = if (terminalCanceled) "canceled" else if (canceled && state != "applied") record.optString("phase") else receipt.getString("phase")
                val savedMessage = if (terminalCanceled) "canceled_before_write" else if (canceled && state != "applied") "canceled_write_requires_review" else receipt.optString("message").take(160)
                record.put("state", savedState).put("phase", savedPhase).put("tableId", id)
                    .put("message", savedMessage).put("updatedAt", Instant.now().toString())
                if (id.isNotEmpty()) record.getJSONObject("bindings").put(record.getString("owner") + ":" + record.getString("termCode"), id)
                if (isFinal) record.put("token", "").put("leaseUntil", 0).put("nextAttempt", System.currentTimeMillis() + 60_000)
                write(record)
                reply(record)
            }
            else -> throw IllegalArgumentException("unsupported_method")
        }
    }

    private fun authenticate(host: Boolean) {
        if (!host) {
            check(Binder.getCallingUid() == Process.myUid()) { "unauthorized_caller" }
            return
        }
        val packageName = "com.miui.voiceassist"
        val pm = requireNotNull(context).packageManager
        check(pm.getPackagesForUid(Binder.getCallingUid())?.contains(packageName) == true) { "unauthorized_host" }
        val info = pm.getApplicationInfo(packageName, 0)
        check(info.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) { "host_not_system_app" }
    }
    private fun validate(raw: String): JSONObject {
        require(raw.toByteArray(Charsets.UTF_8).size <= 700_000) { "payload_too_large" }
        val p = JSONObject(raw)
        require(p.getInt("schema") == 1 && p.getString("source") == CLIENT)
        require(HEX.matches(p.getString("owner")) && HEX.matches(p.getString("revision")))
        require(Regex("[0-9]{6,12}").matches(p.getString("termCode")))
        require(p.getString("termLabel").length in 1..120)
        Instant.parse(p.getString("retrievedAt"))
        val monday = LocalDate.parse(p.getString("weekOneMonday"))
        require(monday.dayOfWeek == DayOfWeek.MONDAY)
        val t = p.getJSONObject("timer")
        require(t.getInt("totalWeek") in 1..60)
        require(t.getLong("startSemester") == monday.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli())
        require(!t.getBoolean("startWithSunday"))
        t.getBoolean("showWeekend")
        val sections = t.getJSONArray("sections")
        require(sections.length() == 14)
        val time = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
        for (i in 0 until 14) {
            val s = sections.getJSONObject(i)
            require(s.getInt("section") == i + 1)
            require(time.matches(s.getString("startTime")) && time.matches(s.getString("endTime")))
            require(s.getString("startTime") < s.getString("endTime"))
        }
        val starts = (0 until sections.length()).map { sections.getJSONObject(it).getString("startTime") }
        require(t.getInt("forenoon") == starts.count { it < "13:00" })
        require(t.getInt("afternoon") == starts.count { it >= "13:00" && it < "18:00" })
        require(t.getInt("night") == starts.count { it >= "18:00" })
        val courses = p.getJSONArray("courses")
        require(courses.length() in 1..2000) { "empty_or_excessive_courses" }
        for (i in 0 until courses.length()) {
            val c = courses.getJSONObject(i)
            require(c.getString("name").isNotBlank() && c.getString("name").length <= 300)
            require(c.getString("teacher").length <= 300 && c.getString("position").length <= 300)
            require(c.getInt("day") in 1..7)
            for ((key, max) in listOf("sections" to 14, "weeks" to t.getInt("totalWeek"))) {
                val a = c.getJSONArray(key)
                val values = (0 until a.length()).map { a.getInt(it) }
                require(values.isNotEmpty() && values.all { it in 1..max } && values.distinct().size == values.size)
            }
        }
        return p
    }
    private fun read(): JSONObject? = if (journal.baseFile.exists()) JSONObject(journal.openRead().bufferedReader().use { it.readText() }) else null
    private fun write(record: JSONObject) {
        val file = journal
        val output = file.startWrite()
        try { output.write(record.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    private fun reply(record: JSONObject?) = Bundle().apply {
        putInt("schema", 1)
        putString("state", record?.optString("state") ?: "pending")
        putBoolean("cancelRequested", record?.optBoolean("cancelRequested") == true)
        putBoolean("allowMutation", record != null && !record.optBoolean("cancelRequested"))
        for (key in listOf("revision", "owner", "termCode", "tableId", "message", "updatedAt", "phase")) putString(key, record?.optString(key).orEmpty())
    }
    private fun wakeHost() {
        val identity = Binder.clearCallingIdentity()
        try {
            val url = "voiceassist://aiweb/?source=widget&flag=268468224&url=" + Uri.encode("https://i.ai.mi.com/h5/precache/ai-schedule/")
            requireNotNull(context).startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.miui.voiceassist").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { /* durable accepted state remains visible to client */ }
        finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = throw UnsupportedOperationException()
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
