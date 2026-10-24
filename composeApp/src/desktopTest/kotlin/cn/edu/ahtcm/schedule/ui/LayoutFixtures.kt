package cn.edu.ahtcm.schedule.ui

import cn.edu.ahtcm.schedule.core.*
import java.time.LocalDate
import java.time.YearMonth
import org.apache.poi.hssf.usermodel.HSSFWorkbook

internal val layoutTerm = AcademicTerm("202601", "2026-2027-1")
internal fun layoutCalendar(): AcademicCalendarSnapshot = AcademicCalendarSnapshot(layoutTerm, (0..139).map {
    val date = LocalDate.parse("2026-08-31").plusDays(it.toLong())
    AcademicCalendarDay(date.toString(), it / 7 + 1, if (date.toString() == "2026-10-23") "学校日期备注" else "")
}, "2026-10-24T00:00:00Z")

internal fun layoutCalendarJson(): String {
    fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val months = layoutCalendar().days.groupBy { YearMonth.parse(it.date.take(7)) }.map { (month, days) ->
        val header = "[{\"type\":\"head\",\"value\":\"周次\"}," + (1..7).joinToString(",") { "{\"type\":\"xq\",\"value\":\"$it\"}" } + "]"
        val rows = days.groupBy { it.week }.map { (week, weekDays) ->
            val cells = (1..7).map { weekday ->
                val day = weekDays.firstOrNull { LocalDate.parse(it.date).dayOfWeek.value == weekday }
                if (day == null) "{\"type\":\"day\",\"value\":\"\"}" else
                    "{\"type\":\"day\",\"value\":\"${LocalDate.parse(day.date).dayOfMonth}\",\"fullText\":\"${day.date}\",\"month\":\"$month\",\"bz\":${quote(day.note)},\"zt\":\"1\"}"
            }
            "[{\"type\":\"zc\",\"value\":\"$week\"},${cells.joinToString(",")}]"
        }
        "[$header,${rows.joinToString(",")}]"
    }
    return "{\"code\":0,\"data\":[${months.joinToString(",")}] }"
}

internal fun layoutXls(): ByteArray = HSSFWorkbook().use { book ->
    val sheet = book.createSheet("课表")
    fun cell(row: Int, column: Int, value: String) { (sheet.getRow(row) ?: sheet.createRow(row)).createCell(column).setCellValue(value) }
    cell(0, 0, "匿名2026-2027-1课表")
    cell(1, 0, "周次\n节次"); cell(1, 1, "第8周")
    cell(2, 0, "")
    repeat(7) { cell(2, it + 1, "10-${19 + it}") }
    cell(3, 0, "星期")
    val names = listOf("第一", "第二", "第三", "第四", "第五", "第六", "第七")
    names.forEachIndexed { i, name -> cell(i + 4, 0, "${name}大节") }
    fun course(row: Int, day: Int, name: String, sections: String, room: String) = cell(row, day, "$name[理论]\n示例教师\n8-$sections\n$room\n示例教学内容，可在详情中完整查看")
    course(4, 1, "基础护理学", "0102", "教学楼101")
    course(6, 1, "计算机基础", "0506", "机房201")
    course(5, 2, "中医基础", "0304", "教学楼203")
    course(7, 2, "大学英语", "0708", "教学楼305")
    course(4, 3, "正常人体解剖学", "0102", "教学楼202")
    course(6, 3, "护理实验", "05060708", "实训室4")
    course(5, 4, "大学英语", "0304", "教学楼305")
    course(4, 5, "思想政治理论", "0102", "教学楼301")
    java.io.ByteArrayOutputStream().also { book.write(it) }.toByteArray()
}
