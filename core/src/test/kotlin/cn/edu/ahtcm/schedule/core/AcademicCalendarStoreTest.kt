package cn.edu.ahtcm.schedule.core

import java.nio.file.Files
import kotlin.test.*

class AcademicCalendarStoreTest {
    private val term = AcademicTerm("202601", "2026-2027-1")
    private fun sample() = AcademicCalendarParser.parse(calendarJson(), term)

    @Test fun `round trip retains school term options and multiple calendars`() {
        val dir = Files.createTempDirectory("academic-calendar")
        try {
            val store = AcademicCalendarStore(dir)
            val other = AcademicTerm("202602", "2026-2027-2")
            store.saveTerms(AcademicCalendarTerms(listOf(term, other), term.code))
            store.save(sample())
            store.save(sample().copy(term = other))
            val restored = AcademicCalendarStore(dir)
            assertEquals(2, restored.snapshots().size)
            assertEquals(sample().days, restored.snapshot(term.code)!!.days)
            assertEquals(term.code, restored.availableTerms()!!.currentCode)
            assertEquals(listOf(term, other), restored.availableTerms()!!.terms)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `failed save and invalid calendar preserve last successful snapshot`() {
        val dir = Files.createTempDirectory("academic-calendar-failure")
        try {
            val store = AcademicCalendarStore(dir)
            val before = sample()
            store.save(before)
            assertFails { store.save(before.copy(days = before.days.filterIndexed { index, _ -> index != 3 })) }
            assertFailsWith<SchoolException> { store.save(before.copy(days = before.days.take(7))) }
            assertEquals(before, store.snapshot(term.code))
            val file = dir.resolve("academic-calendar.json")
            Files.delete(file)
            Files.createDirectory(file)
            Files.writeString(file.resolve("block"), "block atomic replacement")
            assertFails { store.save(before.copy(retrievedAt = "2026-10-25T00:00:00Z")) }
            assertEquals(before, store.snapshot(term.code))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `corrupt cache is reported and accounts never share calendar snapshots`() {
        val dir = Files.createTempDirectory("academic-calendar-accounts")
        try {
            val accounts = AccountStorage(dir)
            accounts.select("anonymous-a")
            accounts.academicCalendar.save(sample())
            accounts.select("anonymous-b")
            assertTrue(accounts.academicCalendar.snapshots().isEmpty())
            accounts.select("anonymous-a")
            assertEquals(14, accounts.academicCalendar.snapshot(term.code)!!.days.size)
            val bad = Files.createDirectory(dir.resolve("bad"))
            Files.writeString(bad.resolve("academic-calendar.json"), "{broken}")
            assertNotNull(AcademicCalendarStore(bad).loadError)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `cancel at commit leaves both disk and memory unchanged and removes pending file`() {
        val dir = Files.createTempDirectory("academic-calendar-commit")
        try {
            val store = AcademicCalendarStore(dir)
            val before = sample()
            store.save(before)
            var checks = 0
            assertFailsWith<IllegalStateException> {
                store.save(before.copy(retrievedAt = "2026-10-25T00:00:00Z")) {
                    checks++
                    check(checks < 2) { "operation canceled before commit" }
                }
            }
            assertEquals(2, checks)
            assertEquals(before, store.snapshot(term.code))
            assertEquals(before, AcademicCalendarStore(dir).snapshot(term.code))
            Files.list(dir).use { assertEquals(listOf("academic-calendar.json"), it.map { path -> path.fileName.toString() }.toList()) }
        } finally { dir.toFile().deleteRecursively() }
    }
}
