package cn.edu.ahtcm.schedule.core

import java.nio.file.Files
import kotlin.test.*

/** Optional read-only verification. No credentials or personal records become fixtures. */
class AcademicCalendarLiveTest {
    @Test fun `authorized school calendar matches validated cached snapshot`() {
        val account = System.getenv("AHUCM_LIVE_ACCOUNT")
        val password = System.getenv("AHUCM_LIVE_PASSWORD")
        org.junit.jupiter.api.Assumptions.assumeTrue(account != null && password != null, "Live verification is opt-in")
        val client = SchoolClient()
        val dir = Files.createTempDirectory("academic-calendar-live")
        try {
            client.login(account!!, password!!)
            val terms = client.fetchAcademicCalendarTerms()
            val term = terms.terms.single { it.code == terms.currentCode }
            val snapshot = client.fetchAcademicCalendar(term)
            assertTrue(snapshot.days.isNotEmpty())
            val store = AcademicCalendarStore(dir)
            store.saveTerms(terms)
            store.save(snapshot)
            assertEquals(snapshot, AcademicCalendarStore(dir).snapshot(term.code))
            println("Official academic calendar: ${snapshot.term.label}; ${snapshot.days.size} dates; ${snapshot.days.first().date} through ${snapshot.days.last().date}; ${snapshot.days.count { it.note.isNotBlank() }} annotated dates")
        } finally {
            client.logout()
            dir.toFile().deleteRecursively()
        }
    }
}
