package com.yuzakikokuban.ahucm

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cn.edu.ahtcm.schedule.core.CalendarEvent
import cn.edu.ahtcm.schedule.core.CalendarExport
import cn.edu.ahtcm.schedule.core.CredentialProtector
import cn.edu.ahtcm.schedule.core.ScheduleSnapshot
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keys remain in AndroidKeyStore; the stored payload is a 12-byte IV followed by ciphertext/tag. */
class AndroidCredentialProtector : CredentialProtector {
    override fun encrypt(clear: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        check(cipher.iv.size == IV_BYTES) { "设备不支持所需的凭据加密格式" }
        return cipher.iv + cipher.doFinal(clear)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        require(sealed.size >= IV_BYTES + TAG_BITS / 8) { "保存的登录信息已损坏，请重新登录" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(TAG_BITS, sealed.copyOfRange(0, IV_BYTES)))
        // Authentication failure propagates: never return unauthenticated or partial plaintext.
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private fun key(create: Boolean): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null)
        if (existing != null) return@synchronized existing as SecretKey
        check(create) { "登录加密密钥已失效，请重新登录" }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build())
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "com.yuzakikokuban.ahucm.login"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        val KEY_LOCK = Any()
    }
}

/** Call on a worker thread after the Activity has obtained calendar permissions. */
class AndroidCalendarSync(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    fun hasPermissions(): Boolean = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Returns the verified number of course events. Each owner and term has its own local calendar. */
    fun sync(snapshot: ScheduleSnapshot, owner: String): Int = synchronized(SYNC_LOCK) {
        check(hasPermissions()) { "请先允许读取和写入日历，再导入课表" }
        require(OWNER_PATTERN.matches(owner)) { "课表账号标识无效，请重新登录" }
        require(snapshot.termCode.isNotBlank()) { "课表缺少学期信息，无法导入日历" }
        // Validate the complete input before creating or changing any calendar rows.
        val desired = CalendarExport.events(snapshot, owner)
        require(desired.all { OWNER_PATTERN.matches(it.uid) && it.endMillis > it.startMillis }) { "课表事件信息无效" }
        val account = "ahucm:$owner"
        val calendarName = "$APP_PACKAGE:$owner:${snapshot.termCode}"
        val existingCalendar = findCalendar(account, calendarName)
        val existing = existingCalendar?.let { readEvents(it) }.orEmpty()
        val operations = arrayListOf<ContentProviderOperation>()
        if (existingCalendar == null) {
            operations += ContentProviderOperation.newInsert(calendarUri(account)).withValues(ContentValues().apply {
                put(Calendars.ACCOUNT_NAME, account)
                put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(Calendars.NAME, calendarName)
                put(Calendars.CALENDAR_DISPLAY_NAME, "安中课表")
                put(Calendars.OWNER_ACCOUNT, account)
                put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
                put(Calendars.CALENDAR_COLOR, 0xff4d7c69.toInt())
                put(Calendars.CALENDAR_TIME_ZONE, "Asia/Shanghai")
                put(Calendars.VISIBLE, 1)
                put(Calendars.SYNC_EVENTS, 1)
            }).build()
        }
        val expectedIds = mutableMapOf<String, Long>()
        val insertedOperations = mutableMapOf<String, Int>()
        val grouped = existing.groupBy { it.uid }
        val wanted = desired.map { it.uid }.toSet()
        desired.forEach { event ->
            val old = grouped[event.uid]?.firstOrNull()
            if (old == null) {
                val builder = ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(values(event))
                if (existingCalendar == null) builder.withValueBackReference(Events.CALENDAR_ID, 0)
                else builder.withValue(Events.CALENDAR_ID, existingCalendar)
                insertedOperations[event.uid] = operations.size
                operations += builder.build()
            } else {
                expectedIds[event.uid] = old.id
                operations += ContentProviderOperation.newUpdate(Events.CONTENT_URI)
                    .withSelection(eventSelection(), eventArgs(existingCalendar!!, old))
                    .withValues(values(event)).withExpectedCount(1).build()
            }
        }
        // Remove obsolete or duplicate tagged rows only. Untagged/user-created rows are untouched.
        grouped.forEach { (uid, rows) ->
            val removals = if (uid in wanted) rows.drop(1) else rows
            removals.forEach { row ->
                operations += ContentProviderOperation.newDelete(Events.CONTENT_URI)
                    .withSelection(eventSelection(), eventArgs(existingCalendar!!, row))
                    .withExpectedCount(1).build()
            }
        }
        val results = if (operations.isEmpty()) emptyArray() else resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        val calendarId = existingCalendar ?: results.firstOrNull()?.uri?.let(ContentUris::parseId)
            ?: error("日历未返回创建结果，请检查系统日历后重试")
        insertedOperations.forEach { (uid, index) ->
            expectedIds[uid] = results[index].uri?.let(ContentUris::parseId)
                ?: error("日历未返回课程写入结果，请检查系统日历后重试")
        }
        check(findCalendar(account, calendarName) == calendarId) { "日历写入后校验失败，请检查系统日历后重试" }
        val receipt = readEvents(calendarId)
        check(receipt.size == desired.size && receipt.map { it.uid }.toSet().size == receipt.size &&
            desired.all { event -> receipt.any { it.uid == event.uid && it.id == expectedIds[event.uid] &&
                it.start == event.startMillis && it.end == event.endMillis } }) {
            "日历写入后校验失败，请检查系统日历后重试"
        }
        desired.size
    }

    private fun findCalendar(account: String, name: String): Long? {
        val selection = "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.NAME}=? AND ${Calendars.OWNER_ACCOUNT}=?"
        val cursor = resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID), selection,
            arrayOf(account, CalendarContract.ACCOUNT_TYPE_LOCAL, name, account), null)
            ?: error("无法读取应用课表日历")
        return cursor.use {
            check(it.count <= 1) { "检测到重复的课表日历，请先在系统日历中整理后重试" }
            if (it.moveToFirst()) it.getLong(0) else null
        }
    }

    private data class StoredEvent(val id: Long, val uid: String, val start: Long, val end: Long)

    private fun readEvents(calendarId: Long): List<StoredEvent> {
        val cursor = resolver.query(Events.CONTENT_URI,
            arrayOf(Events._ID, Events.CUSTOM_APP_URI, Events.DTSTART, Events.DTEND),
            "${Events.CALENDAR_ID}=? AND ${Events.CUSTOM_APP_PACKAGE}=? AND ${Events.CUSTOM_APP_URI} LIKE ? AND ${Events.DELETED}=0",
            arrayOf(calendarId.toString(), APP_PACKAGE, "$EVENT_PREFIX%"), "${Events._ID} ASC")
            ?: error("无法读取应用课表事件")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val uri = it.getString(1) ?: continue
                    val uid = uri.removePrefix(EVENT_PREFIX)
                    if (uri == EVENT_PREFIX + uid && OWNER_PATTERN.matches(uid)) {
                        add(StoredEvent(it.getLong(0), uid, it.getLong(2), it.getLong(3)))
                    }
                }
            }
        }
    }

    private fun values(event: CalendarEvent) = ContentValues().apply {
        put(Events.TITLE, event.title)
        put(Events.EVENT_LOCATION, event.location)
        put(Events.DESCRIPTION, event.description)
        put(Events.DTSTART, event.startMillis)
        put(Events.DTEND, event.endMillis)
        put(Events.EVENT_TIMEZONE, "Asia/Shanghai")
        put(Events.EVENT_END_TIMEZONE, "Asia/Shanghai")
        put(Events.ALL_DAY, 0)
        // Course exports are individual occurrences, even if a calendar editor changed an old row.
        putNull(Events.DURATION)
        putNull(Events.RRULE)
        putNull(Events.RDATE)
        putNull(Events.EXRULE)
        putNull(Events.EXDATE)
        put(Events.CUSTOM_APP_PACKAGE, APP_PACKAGE)
        put(Events.CUSTOM_APP_URI, EVENT_PREFIX + event.uid)
    }

    private fun eventSelection() = "${Events.CALENDAR_ID}=? AND ${Events._ID}=? AND ${Events.CUSTOM_APP_PACKAGE}=? AND ${Events.CUSTOM_APP_URI}=?"

    private fun eventArgs(calendarId: Long, row: StoredEvent) =
        arrayOf(calendarId.toString(), row.id.toString(), APP_PACKAGE, EVENT_PREFIX + row.uid)

    private fun calendarUri(account: String): Uri = Calendars.CONTENT_URI.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    private companion object {
        const val APP_PACKAGE = "com.yuzakikokuban.ahucm"
        const val EVENT_PREFIX = "ahucmschedule://course/"
        val OWNER_PATTERN = Regex("[0-9a-f]{64}")
        val SYNC_LOCK = Any()
    }
}
