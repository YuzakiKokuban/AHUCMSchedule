package cn.edu.ahtcm.schedule.core

import java.nio.file.Files
import kotlin.test.*

/** Explicitly opt in locally. Credentials are read only from the process environment. */
class SchoolLiveTest {
    @Test fun `authorized live account downloads and validates timetable and reads notices`() {
        val account = System.getenv("AHUCM_LIVE_ACCOUNT")
        val password = System.getenv("AHUCM_LIVE_PASSWORD")
        org.junit.jupiter.api.Assumptions.assumeTrue(account != null && password != null, "Live verification is opt-in")
        val client = SchoolClient()
        client.login(account!!, password!!)
        val downloaded = client.downloadSchoolTimetable()
        val dir = Files.createTempDirectory("school-live")
        try {
            val repository = ScheduleRepository(dir)
            val result = repository.synchronizeSchool { downloaded }
            assertTrue(result is UpdateResult.Success, "Official timetable did not pass validation")
            assertTrue(repository.snapshot()!!.lessons.isNotEmpty())
            assertEquals("school", repository.snapshot()!!.source)
            assertEquals(14, repository.snapshot()!!.periods.size)
            println("Official period times: " + downloaded.periods.joinToString { "${it.section} ${it.start}-${it.end}" })
            val raw = client.fetchNotices()
            val parsed = NoticeParser().parse(raw)
            println("Live verification: ${repository.snapshot()!!.lessons.size} lesson occurrences; ${raw.size} messages; ${parsed.size} distinct adjustment notices")
        } finally { dir.toFile().deleteRecursively() }
    }
}
