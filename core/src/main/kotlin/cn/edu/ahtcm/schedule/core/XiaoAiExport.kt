package cn.edu.ahtcm.schedule.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.*
import java.time.temporal.ChronoUnit

@Serializable data class XiaoAiCourse(val name: String, val teacher: String, val position: String, val day: Int, val sections: List<Int>, val weeks: List<Int>)
@Serializable data class XiaoAiSection(val section: Int, val startTime: String, val endTime: String)
@Serializable data class XiaoAiTimer(val totalWeek: Int, val startSemester: Long, val sections: List<XiaoAiSection>, val startWithSunday: Boolean = false, val showWeekend: Boolean = true, val forenoon: Int = 4, val afternoon: Int = 6, val night: Int = 4)
@Serializable data class XiaoAiPayload(val owner: String, val termCode: String, val revision: String, val retrievedAt: String, val weekOneMonday: String, val termLabel: String, val courses: List<XiaoAiCourse>, val timer: XiaoAiTimer, val schema: Int = 1, val source: String = "com.yuzakikokuban.ahucm")

object XiaoAiExport {
    private val json = Json { encodeDefaults = true }
    fun payload(snapshot: ScheduleSnapshot, owner: String): XiaoAiPayload {
        require(owner.matches(Regex("[0-9a-f]{64}")))
        validatePeriods(snapshot.periods)
        require(snapshot.lessons.isNotEmpty())
        val monday = LocalDate.parse(snapshot.weekOneMonday)
        val courses = snapshot.lessons.groupBy {
            require(ChronoUnit.WEEKS.between(monday, LocalDate.parse(it.date)) + 1 == it.week.toLong())
            listOf(it.name, it.teachers, it.location, LocalDate.parse(it.date).dayOfWeek.value.toString(), it.sections.distinct().sorted().joinToString(","))
        }.map { (key, lessons) -> XiaoAiCourse(key[0], key[1], key[2], key[3].toInt(), lessons.first().sections.distinct().sorted(), lessons.map { it.week }.distinct().sorted()) }
            .sortedWith(compareBy({ it.day }, { it.sections.first() }, { it.name }, { it.teacher }, { it.position }))
        val totalWeek = maxOf(snapshot.lessons.maxOf { it.week }, snapshot.calendarEndDate.takeIf { it.isNotBlank() }?.let { (ChronoUnit.WEEKS.between(monday,LocalDate.parse(it)) + 1).toInt() } ?: 1)
        // Use the same 13:00 / 18:00 boundaries as XiaoAi's official timer importer.
        val morning = snapshot.periods.count { LocalTime.parse(it.start) < LocalTime.of(13, 0) }
        val afternoon = snapshot.periods.count { LocalTime.parse(it.start) >= LocalTime.of(13, 0) && LocalTime.parse(it.start) < LocalTime.of(18, 0) }
        val timer = XiaoAiTimer(totalWeek, monday.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(), snapshot.periods.sortedBy { it.section }.map { XiaoAiSection(it.section,it.start,it.end) }, forenoon=morning, afternoon=afternoon, night=snapshot.periods.size-morning-afternoon)
        // Adapter migrations must recheck the target even when school data is unchanged.
        val revision = digest("timer-readback-v2|$owner|${snapshot.termCode}|${json.encodeToString(courses)}|${json.encodeToString(timer)}")
        return XiaoAiPayload(owner,snapshot.termCode,revision,snapshot.retrievedAt,snapshot.weekOneMonday,snapshot.termLabel,courses,timer)
    }
    fun json(payload: XiaoAiPayload) = json.encodeToString(payload)
}
