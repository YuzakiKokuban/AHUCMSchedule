package cn.edu.ahtcm.schedule.ui

import cn.edu.ahtcm.schedule.core.*
import java.net.URI
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.Test

class UnifiedSchoolSyncTest {
    @Test fun `saved current timetable reaches integrations before calendar and notice requests`() = runBlocking {
        val dir = Files.createTempDirectory("immediate-integration")
        try {
            for (requested in listOf(null, layoutTerm)) {
                val requests = mutableListOf<SchoolRequest>()
                var called = false
                val platform = object : PlatformActions {
                    override val repository = ScheduleRepository(dir)
                    override val academicCalendarStore = AcademicCalendarStore(dir)
                    override val school = school(requests)
                    override val sessionReady = true
                    override fun onScheduleSynchronized(snapshot: ScheduleSnapshot): List<String> {
                        assertTrue(requests.any { URI(it.url).path == "/new/printkb/prtxskb" })
                        assertFalse(requests.any { URI(it.url).path == "/new/student/xlxx/xlxxlist.page" })
                        assertEquals(snapshot, repository.snapshot(snapshot.termCode))
                        called = true
                        return emptyList()
                    }
                    override fun openSchool() = Unit
                    override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) = Unit
                }
                synchronizeSchool(platform, requested)
                assertTrue(called)
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `one sync fetches timetable and calendar for chosen term while calendar page is absent`() = runBlocking {
        val dir = Files.createTempDirectory("unified-sync")
        try {
            val requests = mutableListOf<SchoolRequest>()
            var integrations = 0
            val platform = object : PlatformActions {
                override val repository = ScheduleRepository(dir)
                override val academicCalendarStore = AcademicCalendarStore(dir)
                override val school = school(requests)
                override val sessionReady = true
                override fun onScheduleSynchronized(snapshot: ScheduleSnapshot): List<String> { integrations++; return emptyList() }
                override fun openSchool() = Unit
                override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) = Unit
            }
            val result = synchronizeSchool(platform, null)
            assertIs<UpdateResult.Success>(result.schedule)
            assertEquals(140, result.calendar!!.days.size)
            assertEquals(layoutTerm, result.term)
            assertEquals(1, integrations)
            assertTrue(requests.any { URI(it.url).path == "/new/printkb/prtxskb" })
            assertTrue(requests.any { URI(it.url).path == "/new/xlxx/data" && it.body == "xnxqdm=202601" })
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `historical selected term refreshes both sources without replacing external active calendars`() = runBlocking {
        val dir = Files.createTempDirectory("history-sync")
        try {
            val requests = mutableListOf<SchoolRequest>()
            var integrations = 0
            val platform = object : PlatformActions {
                override val repository = ScheduleRepository(dir)
                override val academicCalendarStore = AcademicCalendarStore(dir)
                override val school = school(requests, current = "202602")
                override val sessionReady = true
                override fun onScheduleSynchronized(snapshot: ScheduleSnapshot): List<String> { integrations++; return emptyList() }
                override fun openSchool() = Unit
                override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) = Unit
            }
            val result = synchronizeSchool(platform, layoutTerm)
            assertIs<UpdateResult.Success>(result.schedule)
            assertEquals(layoutTerm, result.calendar!!.term)
            assertEquals(0, integrations)
            assertTrue(requests.filter { it.url.contains("week.page") }.all { it.url.endsWith("xnxqdm=202601") })
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `calendar failure preserves its cache independently of successful timetable and vice versa`() = runBlocking {
        val dir = Files.createTempDirectory("partial-unified-sync")
        try {
            val repository = ScheduleRepository(dir)
            assertIs<UpdateResult.Success>(repository.importXls(layoutXls()))
            val calendarStore = AcademicCalendarStore(dir)
            calendarStore.save(layoutCalendar())
            var failCalendar = true
            val requests = mutableListOf<SchoolRequest>()
            val platform = object : PlatformActions {
                override val repository = repository
                override val academicCalendarStore = calendarStore
                override val school get() = school(requests, failCalendar = failCalendar, failTimetable = !failCalendar)
                override val sessionReady = true
                override fun openSchool() = Unit
                override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) = Unit
            }
            val first = synchronizeSchool(platform, layoutTerm)
            assertIs<UpdateResult.Success>(first.schedule)
            assertEquals(layoutCalendar(), first.calendar)
            assertTrue(first.calendarError!!.contains("尚未发布"))
            failCalendar = false
            val before = repository.snapshot(layoutTerm.code)
            val second = synchronizeSchool(platform, layoutTerm)
            assertIs<UpdateResult.Failure>(second.schedule)
            assertNull(second.calendarError)
            assertEquals(before, repository.snapshot(layoutTerm.code))
            assertEquals(140, second.calendar!!.days.size)
        } finally { dir.toFile().deleteRecursively() }
    }

    private fun school(requests: MutableList<SchoolRequest>, current: String = "202601", failCalendar: Boolean = false, failTimetable: Boolean = false) = SchoolClient(SchoolTransport { request ->
        requests += request
        val periods = (1..14).joinToString(",") { """{"jcdm":"$it","qssj":"08:30:00","jssj":"09:10:00"}""" }
        val body = when (URI(request.url).path) {
            "/new/student/xsgrkb/main.page" -> "url: '/new/student/xsgrkb/week.page?xnxqdm=$current'"
            "/new/student/xsgrkb/week.page" -> """<select id='xnxqdm'><option value='202601' selected>2026-2027-1</option></select><script>var businessHours = $.parseJSON('[$periods]');</script>"""
            "/new/student/xlxx/xlxxlist.page" -> """<select id='xnxqdm'><option value='202601' ${if (current == "202601") "selected" else ""}>2026-2027-1</option><option value='202602' ${if (current == "202602") "selected" else ""}>2026-2027-2</option></select>"""
            "/new/xlxx/data" -> if (failCalendar) """{"code":0,"data":[]}""" else layoutCalendarJson()
            "/new/printkb/prtxskb" -> "invalid timetable"
            else -> error("Unexpected path")
        }
        SchoolResponse(200, emptyMap(), if (URI(request.url).path == "/new/printkb/prtxskb" && !failTimetable) layoutXls() else body.toByteArray())
    })
}
