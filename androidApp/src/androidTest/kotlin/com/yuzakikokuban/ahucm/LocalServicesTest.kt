package com.yuzakikokuban.ahucm

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.ContextWrapper
import android.os.Build
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.ahtcm.schedule.core.ClassPeriod
import cn.edu.ahtcm.schedule.core.EncryptedCredentialStore
import cn.edu.ahtcm.schedule.core.Lesson
import cn.edu.ahtcm.schedule.core.SavedLogin
import cn.edu.ahtcm.schedule.core.ScheduleSnapshot
import cn.edu.ahtcm.schedule.core.SchoolWebSession
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device tests use anonymous fixtures and an exclusively reserved test calendar. */
@RunWith(AndroidJUnit4::class)
class LocalServicesTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver

    @Test
    fun browserSessionRoundTripsInIsolatedEncryptedStore() {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "browser-session-test-").toFile()
        val isolatedContext = object : ContextWrapper(context) {
            override fun getFilesDir(): File = directory
        }
        val session = SchoolWebSession("2099123456", mapOf(
            "https://jwxt.ahtcm.edu.cn" to "JSESSIONID=TEST-ONLY-anonymous-cookie"),casAuthenticated=true)
        val store = AndroidBrowserSessionStore(isolatedContext)
        try {
            assertNull(store.load())
            store.save(session)
            assertEquals(session, AndroidBrowserSessionStore(isolatedContext).load())
            val path = File(directory, "schedule/browser-session.bin").toPath()
            val bytes = Files.readAllBytes(path)
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("TEST-ONLY-anonymous-cookie"))
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            Files.write(path, bytes)
            assertTrue(runCatching { AndroidBrowserSessionStore(isolatedContext).load() }.isFailure)
            store.clear()
            assertNull(store.load())
        } finally {
            store.clear()
            File(directory, "schedule").delete()
            directory.delete()
        }
    }

    @Test
    fun encryptedLoginSurvivesRecreationAndRejectsTampering() {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "local-services-test-")
        val path = directory.resolve("login.bin")
        val login = SavedLogin("anonymous-device-test", "TEST-ONLY-password-7e81e9f4")
        try {
            EncryptedCredentialStore(path, AndroidCredentialProtector()).save(login)
            assertEquals(login, EncryptedCredentialStore(path, AndroidCredentialProtector()).load())
            val bytes = Files.readAllBytes(path)
            assertTrue("Encrypted payload must contain an IV and authentication tag", bytes.size >= 28)
            assertFalse("Password must not occur in the persisted payload",
                bytes.toString(Charsets.ISO_8859_1).contains(login.password))
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            Files.write(path, bytes)
            assertTrue("Tampered credentials must fail authentication",
                runCatching { EncryptedCredentialStore(path, AndroidCredentialProtector()).load() }.isFailure)
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun calendarSyncIsIdempotentAndPreservesUntaggedEvents() {
        assumeTrue("Runtime permission grants need Android 9 or newer", Build.VERSION.SDK_INT >= 28)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CALENDAR)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_CALENDAR)
        val provider = resolver.acquireContentProviderClient(CalendarContract.AUTHORITY)
        assumeTrue("Device has no CalendarProvider", provider != null)
        provider?.close()
        val sync = AndroidCalendarSync(context)
        assertTrue(sync.hasPermissions())
        // Refuse to touch a pre-existing row, even in the reserved TEST ONLY namespace.
        assertTrue("Reserved test calendar already exists; inspect it before rerunning", testCalendarIds().isEmpty())
        try {
            val snapshot = testSnapshot()
            assertEquals(1, sync.sync(snapshot, TEST_OWNER))
            val calendarId = testCalendarIds().single()
            val first = readEvents(calendarId).single()
            assertEquals("匿名测试课程", first.title)
            assertEquals("测试教室 A101", first.location)
            assertEquals(APP_PACKAGE, first.appPackage)
            assertTrue(first.appUri?.matches(Regex("ahucmschedule://course/[0-9a-f]{64}")) == true)
            val date = LocalDate.parse("2026-10-21")
            val zone = ZoneId.of("Asia/Shanghai")
            assertEquals(date.atTime(7, 0).atZone(zone).toInstant().toEpochMilli(), first.start)
            assertEquals(date.atTime(8, 40).atZone(zone).toInstant().toEpochMilli(), first.end)

            assertEquals(1, sync.sync(snapshot, TEST_OWNER))
            assertEquals(listOf(first), readEvents(calendarId))
            val changed = snapshot.copy(lessons = snapshot.lessons.map { it.copy(location = "测试教室 B202") })
            assertEquals(1, sync.sync(changed, TEST_OWNER))
            val updated = readEvents(calendarId).single()
            assertEquals(first.id, updated.id)
            assertEquals("匿名测试课程", updated.title)
            assertEquals("测试教室 B202", updated.location)

            val untaggedUri = resolver.insert(Events.CONTENT_URI, ContentValues().apply {
                put(Events.CALENDAR_ID, calendarId)
                put(Events.TITLE, "TEST ONLY 未标记事件")
                put(Events.EVENT_LOCATION, "测试位置")
                put(Events.DTSTART, first.start)
                put(Events.DTEND, first.end)
                put(Events.EVENT_TIMEZONE, "Asia/Shanghai")
            }) ?: error("CalendarProvider did not return the untagged test event")
            val untaggedId = ContentUris.parseId(untaggedUri)
            assertEquals(2, readEvents(calendarId).size)
            assertEquals(0, sync.sync(changed.copy(lessons = emptyList()), TEST_OWNER))
            val survivor = readEvents(calendarId).single()
            assertEquals(untaggedId, survivor.id)
            assertEquals("TEST ONLY 未标记事件", survivor.title)
            assertNull(survivor.appPackage)
            assertNull(survivor.appUri)
        } finally {
            // Discover only the exact reserved account/name/owner, then constrain each delete by ID too.
            testCalendarIds().forEach { calendarId ->
                val uri = Calendars.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(Calendars.ACCOUNT_NAME, TEST_ACCOUNT)
                    .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                    .build()
                assertEquals("Only the exact test calendar should be removed", 1,
                    resolver.delete(uri, "${Calendars._ID}=? AND $CALENDAR_SELECTION",
                        arrayOf(calendarId.toString(), *calendarArguments())))
            }
            assertTrue("Test calendar cleanup must be verified", testCalendarIds().isEmpty())
        }
    }

    private fun testSnapshot() = ScheduleSnapshot(
        termCode = TEST_TERM,
        termLabel = "TEST ONLY 学期",
        weekOneMonday = "2026-08-31",
        retrievedAt = "2026-10-23T00:00:00Z",
        lessons = listOf(Lesson("匿名测试课程", "测试", "匿名教师", "测试教室 A101", "TEST ONLY",
            "2026-10-21", 8, listOf(1, 2))),
        periods = (1..14).map { section -> ClassPeriod(section,
            String.format(Locale.ROOT, "%02d:00", section + 6),
            String.format(Locale.ROOT, "%02d:40", section + 6)) }
    )

    private fun calendarArguments() = arrayOf(TEST_ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL, TEST_CALENDAR_NAME, TEST_ACCOUNT)

    private fun testCalendarIds(): List<Long> {
        val cursor = resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID), CALENDAR_SELECTION,
            calendarArguments(), null) ?: error("CalendarProvider calendar query returned null")
        return cursor.use { buildList { while (it.moveToNext()) add(it.getLong(0)) } }
    }

    private data class EventReceipt(val id: Long, val title: String?, val location: String?,
        val appPackage: String?, val appUri: String?, val start: Long, val end: Long)

    private fun readEvents(calendarId: Long): List<EventReceipt> {
        val cursor = resolver.query(Events.CONTENT_URI, arrayOf(Events._ID, Events.TITLE, Events.EVENT_LOCATION,
            Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI, Events.DTSTART, Events.DTEND),
            "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0", arrayOf(calendarId.toString()), "${Events._ID} ASC")
            ?: error("CalendarProvider event query returned null")
        return cursor.use { buildList {
            while (it.moveToNext()) add(EventReceipt(it.getLong(0), it.getString(1), it.getString(2),
                it.getString(3), it.getString(4), it.getLong(5), it.getLong(6)))
        } }
    }

    private companion object {
        const val APP_PACKAGE = "com.yuzakikokuban.ahucm"
        // TEST ONLY SHA-256-shaped owner, never an account or student identifier.
        const val TEST_OWNER = "d4c716f213a8b391cc9efd208c194857956337af74f03fb807a88dcb36216f09"
        const val TEST_TERM = "test-only-localservices-20261023"
        const val TEST_ACCOUNT = "ahucm:$TEST_OWNER"
        const val TEST_CALENDAR_NAME = "$APP_PACKAGE:$TEST_OWNER:$TEST_TERM"
        const val CALENDAR_SELECTION = "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.NAME}=? AND ${Calendars.OWNER_ACCOUNT}=?"
    }
}
