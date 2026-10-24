package cn.edu.ahtcm.schedule.core

import java.time.LocalDate
import java.time.YearMonth
import kotlinx.serialization.json.*
import kotlin.test.*

class AcademicCalendarTest {
    private val term = AcademicTerm("202601", "2026-2027-1")

    @Test fun `parses official weeks across month and year without inferring holidays`() {
        val parsed = AcademicCalendarParser.parse(calendarJson(), term)
        assertEquals(14, parsed.days.size)
        assertEquals("2026-12-28", parsed.days.first().date)
        assertEquals("2027-01-10", parsed.days.last().date)
        assertEquals(1, parsed.days.first().week)
        assertEquals(2, parsed.days.last().week)
        assertEquals("学校备注：以通知为准", parsed.days[4].note)
    }

    @Test fun `parses school listed terms and the explicitly selected term`() {
        val terms = AcademicCalendarParser.terms("""<select id='xnxqdm'><option value='202602'>2026-2027-2</option><option value='202601' selected>2026-2027-1</option></select>""")
        assertEquals("202601", terms.currentCode)
        assertEquals(listOf("202602", "202601"), terms.terms.map { it.code })
        assertFailsWith<SchoolException> { AcademicCalendarParser.terms("<form id='loginForm'></form>") }
        assertFailsWith<SchoolException> { AcademicCalendarParser.terms("<select id='xnxqdm'><option value='evil' selected>x</option></select>") }
    }

    @Test fun `rejects failed empty and malformed responses`() {
        listOf("<html>登录</html>", "{}", """{"code":-1,"data":[]}""", """{"code":0,"data":[]}""",
            calendarJson().replace("2027-01-10", "2027-01-99"),
            calendarJson().replace("2027-01-10", "2027-01-09"),
            calendarJson().replace("2027-01-10", "2027-01-11"),
            calendarJson().replace("\"value\":\"2\"", "\"value\":\"3\""))
            .forEach { assertFailsWith<SchoolException> { AcademicCalendarParser.parse(it, term) } }
    }

    @Test fun `client requests only the selected school term through read only endpoints`() {
        val requests = mutableListOf<SchoolRequest>()
        val client = SchoolClient(SchoolTransport { request ->
            requests += request
            val text = if (request.url.endsWith("xlxxlist.page")) """<select id='xnxqdm'><option value='202601' selected>2026-2027-1</option></select>""" else calendarJson()
            SchoolResponse(200, emptyMap(), text.toByteArray())
        })
        assertEquals(term, client.fetchAcademicCalendarTerms().terms.single())
        assertEquals(14, client.fetchAcademicCalendar(term).days.size)
        assertEquals("https://jwxt.ahtcm.edu.cn/new/xlxx/data", requests.last().url)
        assertEquals("POST", requests.last().method)
        assertEquals("xnxqdm=202601", requests.last().body)
        assertFailsWith<IllegalArgumentException> { client.fetchAcademicCalendar(AcademicTerm("../other", "bad")) }
    }

    @Test fun `incomplete terminal week is rejected even if remaining dates are contiguous`() {
        val root = Json.parseToJsonElement(calendarJson()).jsonObject.toMutableMap()
        val months = root.getValue("data").jsonArray.toMutableList()
        val rows = months.last().jsonArray.toMutableList()
        val row = rows.last().jsonArray.toMutableList()
        row[7] = buildJsonObject { put("type", "day"); put("value", "") }
        rows[rows.lastIndex] = JsonArray(row)
        months[months.lastIndex] = JsonArray(rows)
        root["data"] = JsonArray(months)
        assertFailsWith<SchoolException> { AcademicCalendarParser.parse(JsonObject(root).toString(), term) }
    }
}

/** Generated anonymous school-shaped response: month -> week row -> eight cells. */
internal fun calendarJson(): String {
    val first = LocalDate.of(2026, 12, 28)
    val dates = (0L..13L).map { first.plusDays(it) }
    val months = dates.groupBy { YearMonth.from(it) }
    fun cell(type: String, value: String) = buildJsonObject { put("type", type); put("value", value) }
    return buildJsonObject {
        put("code", 0)
        putJsonArray("data") {
            months.forEach { (month, days) ->
                add(buildJsonArray {
                    add(buildJsonArray { add(cell("head", "周次")); repeat(7) { add(cell("xq", (it + 1).toString())) } })
                    days.groupBy { (java.time.temporal.ChronoUnit.DAYS.between(first, it) / 7 + 1).toInt() }.forEach { (week, weekDays) ->
                        add(buildJsonArray {
                            add(cell("zc", "$week"))
                            (1..7).forEach { weekday ->
                                val day = weekDays.singleOrNull { it.dayOfWeek.value == weekday }
                                add(if (day == null) cell("day", "") else buildJsonObject {
                                    put("type", "day"); put("value", "${day.dayOfMonth}"); put("fullText", day.toString())
                                    put("month", month.toString()); put("zt", "1")
                                    put("bz", if (day == first.plusDays(4)) "学校备注：以通知为准" else "")
                                })
                            }
                        })
                    }
                })
            }
        }
    }.toString()
}
