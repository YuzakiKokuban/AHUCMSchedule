package cn.edu.ahtcm.schedule.core

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

@Serializable
data class AcademicTerm(val code: String, val label: String) {
    init {
        require(code.matches(Regex("20\\d{2}0[12]")))
        val year = code.take(4).toInt()
        require(label == "$year-${year + 1}-${code.last()}")
    }
}

@Serializable data class AcademicCalendarDay(val date: String, val week: Int, val note: String = "")
@Serializable data class AcademicCalendarSnapshot(
    val term: AcademicTerm, val days: List<AcademicCalendarDay>, val retrievedAt: String
)
@Serializable data class AcademicCalendarTerms(val terms: List<AcademicTerm>, val currentCode: String)

internal fun validateAcademicCalendar(snapshot: AcademicCalendarSnapshot) {
    Instant.parse(snapshot.retrievedAt)
    require(snapshot.days.size in 7..366)
    val first = LocalDate.parse(snapshot.days.first().date)
    val last = LocalDate.parse(snapshot.days.last().date)
    require(first.dayOfWeek.value == 1 && last.dayOfWeek.value == 7)
    val monday = first
    snapshot.days.forEachIndexed { index, day ->
        val date = LocalDate.parse(day.date)
        require(date == first.plusDays(index.toLong()))
        require(date.year in snapshot.term.code.take(4).toInt()..snapshot.term.code.take(4).toInt() + 1)
        require(day.week == ChronoUnit.WEEKS.between(monday, date).toInt() + 1)
        require(day.note.length <= 2000)
    }
}

object AcademicCalendarParser {
    fun terms(html: String): AcademicCalendarTerms = try {
        val document = Jsoup.parse(html)
        require(document.selectFirst("form#loginForm") == null)
        val options = document.select("select#xnxqdm option")
        val terms = options.map { AcademicTerm(it.attr("value"), it.text().trim()) }
        require(terms.isNotEmpty() && terms.size <= 100 && terms.distinctBy { it.code }.size == terms.size)
        val selected = options.filter { it.hasAttr("selected") }
        require(selected.size == 1)
        AcademicCalendarTerms(terms, selected.single().attr("value"))
    } catch (_: Exception) {
        throw SchoolException("无法读取学校校历学期，请重新登录或打开教务系统核对")
    }

    fun parse(body: String, term: AcademicTerm, now: Instant = Instant.now()): AcademicCalendarSnapshot {
        try {
            val root = Json.parseToJsonElement(body).jsonObject
            require(root["code"]?.jsonPrimitive?.intOrNull == 0)
            val months = root.getValue("data").jsonArray
            if (months.isEmpty()) throw UnpublishedCalendar()
            require(months.size in 1..13)
            val days = mutableListOf<AcademicCalendarDay>()
            val seenMonths = mutableListOf<YearMonth>()
            months.forEach { month ->
                val rows = month.jsonArray
                require(rows.size in 2..7)
                val header = rows.first().jsonArray
                require(header.size == 8 && header[0].jsonObject.string("type") == "head")
                require(header.drop(1).all { it.jsonObject.string("type") == "xq" })
                val monthDates = mutableListOf<LocalDate>()
                rows.drop(1).forEach { row ->
                    val cells = row.jsonArray
                    require(cells.size == 8 && cells[0].jsonObject.string("type") == "zc")
                    val week = cells[0].jsonObject.string("value").toInt()
                    require(week in 1..53)
                    var occupied = false
                    cells.drop(1).forEachIndexed { index, element ->
                        val cell = element.jsonObject
                        require(cell.string("type") == "day")
                        val value = cell.string("value").trim()
                        if (value.isNotEmpty()) {
                            val date = LocalDate.parse(cell.string("fullText"))
                            require(date.dayOfMonth == value.toInt() && date.dayOfWeek.value == index + 1)
                            require(YearMonth.from(date).toString() == cell.string("month"))
                            val note = Jsoup.parseBodyFragment(cell.string("bz")).text()
                            days += AcademicCalendarDay(date.toString(), week, note)
                            monthDates += date
                            occupied = true
                        } else require(cell["fullText"] == null && cell["month"] == null)
                    }
                    require(occupied)
                }
                require(monthDates.map { YearMonth.from(it) }.distinct().size == 1)
                seenMonths += YearMonth.from(monthDates.first())
            }
            require(seenMonths.zipWithNext().all { (a, b) -> a.plusMonths(1) == b })
            return AcademicCalendarSnapshot(term, days, now.toString()).also(::validateAcademicCalendar)
        } catch (_: UnpublishedCalendar) {
            throw SchoolException("学校尚未发布这个学期的校历")
        } catch (_: Exception) {
            throw SchoolException("学校校历数据校验失败，请重试或打开教务系统核对")
        }
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private class UnpublishedCalendar : Exception()
}
