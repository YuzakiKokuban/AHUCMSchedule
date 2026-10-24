package cn.edu.ahtcm.schedule.core

import java.time.*
import java.time.format.DateTimeFormatter
import java.security.MessageDigest

data class CalendarEvent(val uid: String, val title: String, val location: String, val description: String, val startMillis: Long, val endMillis: Long)
internal fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

object CalendarExport {
    fun events(snapshot: ScheduleSnapshot, owner: String): List<CalendarEvent> {
        validatePeriods(snapshot.periods)
        val zone = ZoneId.of("Asia/Shanghai")
        val periods = snapshot.periods.associateBy { it.section }
        return snapshot.lessons.flatMap { lesson ->
            val runs = mutableListOf<MutableList<Int>>()
            lesson.sections.distinct().sorted().forEach { section ->
                require(section in periods)
                if (runs.lastOrNull()?.last() == section - 1) runs.last().add(section)
                else runs.add(mutableListOf(section))
            }
            runs.map { run ->
                val date = LocalDate.parse(lesson.date)
                val start = date.atTime(LocalTime.parse(periods.getValue(run.first()).start)).atZone(zone).toInstant().toEpochMilli()
                val end = date.atTime(LocalTime.parse(periods.getValue(run.last()).end)).atZone(zone).toInstant().toEpochMilli()
                require(end > start)
                CalendarEvent(digest("$owner|${snapshot.termCode}|${lesson.date}|${run.joinToString(",")}|${lesson.name}|${lesson.teachers}"),
                    lesson.name, lesson.location,
                    listOf(lesson.teachers, lesson.kind, "第 ${lesson.week} 周 · 第 ${run.joinToString("、")} 节", lesson.content).filter { it.isNotBlank() }.joinToString("\n"), start, end)
            }
        }.groupBy { it.uid }.values.map { parallel ->
            parallel.first().copy(
                location = parallel.map { it.location }.distinct().sorted().joinToString(" / "),
                description = parallel.map { it.description }.distinct().sorted().joinToString("\n\n")
            )
        }.sortedWith(compareBy({ it.startMillis }, { it.uid }))
    }

    fun ics(snapshot: ScheduleSnapshot, owner: String): String {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        fun escape(text: String) = text.replace("\\", "\\\\").replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//YuzakiKokuban//AHUCMSchedule//ZH", "CALSCALE:GREGORIAN", "METHOD:PUBLISH", "X-WR-CALNAME:岐黄课表", "X-WR-TIMEZONE:Asia/Shanghai")
        events(snapshot, owner).forEach { event ->
            lines += listOf("BEGIN:VEVENT", "UID:${event.uid}@yuzakikokuban.com", "DTSTAMP:${formatter.format(Instant.parse(snapshot.retrievedAt))}",
                "DTSTART:${formatter.format(Instant.ofEpochMilli(event.startMillis))}", "DTEND:${formatter.format(Instant.ofEpochMilli(event.endMillis))}",
                "SUMMARY:${escape(event.title)}", "LOCATION:${escape(event.location)}", "DESCRIPTION:${escape(event.description)}", "END:VEVENT")
        }
        lines += "END:VCALENDAR"
        // RFC 5545: fold at 75 octets without breaking a UTF-8 code point.
        return lines.joinToString("\r\n", postfix = "\r\n") { line ->
            val folded = StringBuilder(); var bytes = 0
            line.codePoints().forEach { cp ->
                val char = String(Character.toChars(cp)); val size = char.toByteArray(Charsets.UTF_8).size
                if (bytes + size > 75) { folded.append("\r\n "); bytes = 1 }
                folded.append(char); bytes += size
            }
            folded.toString()
        }
    }
}
