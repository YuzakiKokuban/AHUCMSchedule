package cn.edu.ahtcm.schedule.core

import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class TodayScheduleTest {
    private val today = LocalDate.parse("2026-10-21")
    private val periods = (1..14).map {
        val hour = 8 + (it - 1) / 2
        val minute = if (it % 2 == 1) "00" else "30"
        ClassPeriod(it, "$hour:$minute".padStart(5, '0'), "$hour:${if (it % 2 == 1) "20" else "50"}".padStart(5, '0'))
    }
    private fun snapshot(term: String = "202601", date: String = today.toString(), sections: List<Int> = listOf(1,2)) = ScheduleSnapshot(
        term, "2026-2027 第1学期", "2026-08-31", "2026-10-20T00:00:00Z",
        listOf(Lesson("基础护理学", "理论", "示例教师", "教学楼101", "", date, 8, sections)),
        "2027-01-17", "school", periods
    )

    @Test fun `current day ignores historically selected active snapshot and uses matching cached term`() {
        val old = snapshot("202501").copy(weekOneMonday = "2025-08-25", calendarEndDate = "2026-01-11")
        val result = TodaySchedule.project(listOf(old, snapshot()), "202601", Instant.parse("2026-10-21T00:10:00Z"))
        assertEquals("202601", result.termCode)
        assertEquals(today, result.date)
        assertEquals(1, result.courses.size)
        assertEquals(CourseMoment.Ongoing, result.courses.single().moment)
    }

    @Test fun `breaks are upcoming instead of ongoing and course end is exclusive`() {
        val duringBreak = TodaySchedule.project(listOf(snapshot()), "202601", Instant.parse("2026-10-21T00:25:00Z"))
        assertEquals(CourseMoment.Next, duringBreak.courses.single().moment)
        assertEquals(Instant.parse("2026-10-21T00:30:00Z"), duringBreak.nextChange)
        val ended = TodaySchedule.project(listOf(snapshot()), "202601", Instant.parse("2026-10-21T00:50:00Z"))
        assertEquals(CourseMoment.Finished, ended.courses.single().moment)
        assertEquals(Instant.parse("2026-10-21T16:00:00Z"), ended.nextChange)
    }

    @Test fun `non consecutive sections split and parallel courses share next status`() {
        val base = snapshot(sections = listOf(1,2,5,6))
        val result = TodaySchedule.project(listOf(base.copy(lessons = base.lessons + base.lessons.single().copy(name = "实验分组", location = "实训室4"))), "202601", Instant.parse("2026-10-20T23:00:00Z"))
        assertEquals(4, result.courses.size)
        assertEquals(2, result.courses.count { it.moment == CourseMoment.Next })
        assertEquals(listOf(1,2), result.courses.first().sections)
    }

    @Test fun `no periods never guesses times and missing data differs from no courses`() {
        val noTimes = TodaySchedule.project(listOf(snapshot().copy(periods = emptyList())), "202601", Instant.parse("2026-10-21T00:10:00Z"))
        assertEquals(CourseMoment.Unknown, noTimes.courses.single().moment)
        assertNull(noTimes.courses.single().start)
        assertEquals(DayAvailability.NoCourses, TodaySchedule.project(listOf(snapshot(date = "2026-10-22")), null, Instant.parse("2026-10-21T00:00:00Z")).availability)
        assertEquals(DayAvailability.NoCache, TodaySchedule.project(emptyList(), null, Instant.parse("2026-10-21T00:00:00Z")).availability)
        assertEquals(DayAvailability.OutsideCachedTerms, TodaySchedule.project(listOf(snapshot()), null, Instant.parse("2027-02-01T00:00:00Z")).availability)
    }

    @Test fun `Shanghai midnight changes day and never retains yesterday courses`() {
        val result = TodaySchedule.project(listOf(snapshot()), "202601", Instant.parse("2026-10-21T16:01:00Z"))
        assertEquals(LocalDate.parse("2026-10-22"), result.date)
        assertTrue(result.courses.isEmpty())
    }
}
