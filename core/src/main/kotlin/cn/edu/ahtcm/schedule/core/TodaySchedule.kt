package cn.edu.ahtcm.schedule.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class CourseMoment { Ongoing, Next, Later, Finished, Unknown }
enum class DayAvailability { Ready, NoCourses, NoCache, OutsideCachedTerms, InvalidCache }
data class TodayCourse(
    val name: String, val location: String, val sections: List<Int>,
    val start: LocalTime?, val end: LocalTime?, val moment: CourseMoment
)
data class DaySchedule(
    val date: LocalDate, val termCode: String?, val termLabel: String?,
    val retrievedAt: String?, val source: String?, val courses: List<TodayCourse>,
    val availability: DayAvailability, val nextChange: Instant
)

/** Offline projection; browsing a historical term never determines today's widget. */
object TodaySchedule {
    private val zone = ZoneId.of("Asia/Shanghai")

    fun project(snapshots: List<ScheduleSnapshot>, schoolCurrentTerm: String?, now: Instant): DaySchedule {
        val today = now.atZone(zone).toLocalDate()
        val midnight = today.plusDays(1).atStartOfDay(zone).toInstant()
        fun empty(state: DayAvailability, snapshot: ScheduleSnapshot? = null) = DaySchedule(
            today, snapshot?.termCode, snapshot?.termLabel, snapshot?.retrievedAt, snapshot?.source,
            emptyList(), state, midnight
        )
        if (snapshots.isEmpty()) return empty(DayAvailability.NoCache)
        val matching = snapshots.filter { snapshot ->
            runCatching { today in LocalDate.parse(snapshot.weekOneMonday)..LocalDate.parse(snapshot.calendarEndDate) }.getOrDefault(false)
        }
        val snapshot = matching.sortedWith(compareByDescending<ScheduleSnapshot> { it.termCode == schoolCurrentTerm }
            .thenByDescending { it.source == "school" }.thenByDescending { it.retrievedAt }).firstOrNull()
            ?: return empty(DayAvailability.OutsideCachedTerms)
        val lessons = snapshot.lessons.filter { it.date == today.toString() }
        if (lessons.isEmpty()) return empty(DayAvailability.NoCourses, snapshot)
        if (lessons.any { it.name.isBlank() || it.sections.isEmpty() || it.sections.any { section -> section !in 1..14 } })
            return empty(DayAvailability.InvalidCache, snapshot)
        val periods = runCatching {
            validatePeriods(snapshot.periods)
            snapshot.periods.associate { it.section to (LocalTime.parse(it.start) to LocalTime.parse(it.end)) }
        }.getOrDefault(emptyMap())
        data class TimedCourse(val course: TodayCourse, val times: List<Pair<Instant, Instant>>)
        val rows = lessons.flatMap { lesson ->
            val runs = mutableListOf<MutableList<Int>>()
            lesson.sections.distinct().sorted().forEach { section ->
                if (runs.lastOrNull()?.last() == section - 1) runs.last().add(section) else runs.add(mutableListOf(section))
            }
            runs.map { run ->
                val times = run.mapNotNull { section -> periods[section]?.let { (start, end) ->
                    today.atTime(start).atZone(zone).toInstant() to today.atTime(end).atZone(zone).toInstant()
                } }
                val moment = when {
                    times.size != run.size -> CourseMoment.Unknown
                    times.any { (start, end) -> now >= start && now < end } -> CourseMoment.Ongoing
                    times.all { (_, end) -> end <= now } -> CourseMoment.Finished
                    else -> CourseMoment.Later
                }
                TimedCourse(TodayCourse(lesson.name, lesson.location, run.toList(), periods[run.first()]?.first, periods[run.last()]?.second, moment), times)
            }
        }.sortedWith(compareBy({ it.course.sections.first() }, { it.course.name }, { it.course.location }))
        val nextStart = if (rows.any { it.course.moment == CourseMoment.Ongoing }) null
            else rows.flatMap { it.times }.map { it.first }.filter { it > now }.minOrNull()
        val courses = rows.map { row ->
            if (row.course.moment == CourseMoment.Later && row.times.any { it.first == nextStart }) row.course.copy(moment = CourseMoment.Next)
            else row.course
        }
        val nextChange = (rows.flatMap { it.times.flatMap { (start, end) -> listOf(start, end) } }.filter { it > now } + midnight).min()
        return DaySchedule(today, snapshot.termCode, snapshot.termLabel, snapshot.retrievedAt, snapshot.source, courses, DayAvailability.Ready, nextChange)
    }
}
