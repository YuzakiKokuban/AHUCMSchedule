package cn.edu.ahtcm.schedule.core

import java.time.Instant
import java.time.LocalDate
import java.time.DayOfWeek
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.CellType

class XlsScheduleImporter {
    fun parse(bytes: ByteArray, now: Instant = Instant.now()): ScheduleSnapshot {
        if (bytes.size !in 8..(20 * 1024 * 1024) || !bytes.take(8).toByteArray().contentEquals(
                byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))) {
            throw ImportException("请选择教务系统导出的 XLS 文件，当前文件格式不正确")
        }
        try {
            return HSSFWorkbook(bytes.inputStream()).use { book ->
                if (book.numberOfSheets == 0) throw ImportException("课表文件没有工作表")
                val sheet = book.getSheetAt(0)
                if (sheet.lastRowNum > 10000) throw ImportException("课表行数异常")
                fun text(row: Int, col: Int): String {
                    val cell = sheet.getRow(row)?.getCell(col) ?: return ""
                    return when (cell.cellType) {
                        CellType.STRING -> cell.stringCellValue
                        CellType.BLANK -> ""
                        else -> throw ImportException("课表第 ${row + 1} 行格式异常")
                    }.replace("\r\n", "\n").replace('\r', '\n')
                }
                val term = (0..minOf(4, sheet.lastRowNum)).asSequence()
                    .mapNotNull { TERM.find(text(it, 0)) }.firstOrNull()
                    ?: throw ImportException("无法识别课表学期，请使用个人课表的官方导出")
                val startYear = term.groupValues[1].toInt()
                val endYear = term.groupValues[2].toInt()
                val semester = term.groupValues[3].toInt()
                if (endYear != startYear + 1) throw ImportException("课表学年不正确")
                var monday: LocalDate? = null
                var lastDate: LocalDate? = null
                var dates = emptyMap<Int, Pair<LocalDate, Int>>()
                val lessons = linkedSetOf<Lesson>()
                for (r in 1..sheet.lastRowNum) {
                    val row = sheet.getRow(r) ?: continue
                    if (row.lastCellNum > 100) throw ImportException("课表列数异常")
                    val cols = 1 until row.lastCellNum.toInt().coerceAtLeast(1)
                    val dateCells = cols.mapNotNull { c -> DATE_HEADER.matchEntire(text(r, c).trim())?.let { c to it } }
                    if (text(r, 0).isBlank() && dateCells.isNotEmpty()) {
                        val headers = (1 until (sheet.getRow(r - 1)?.lastCellNum?.toInt() ?: 1))
                            .mapNotNull { c -> WEEK_HEADER.matchEntire(text(r - 1, c).trim())?.let { c to it.groupValues[1].toInt() } }
                        if (headers.isEmpty()) throw ImportException("课表日期缺少对应教学周")
                        dates = dateCells.associate { (c, match) ->
                            val header = headers.lastOrNull { it.first <= c } ?: throw ImportException("日期列缺少教学周")
                            val week = header.second
                            if (week !in 1..30) throw ImportException("教学周超出合理范围")
                            val month = match.groupValues[1].toInt()
                            val day = match.groupValues[2].toInt()
                            val year = if (semester == 1 && month >= 8) startYear else endYear
                            val date = LocalDate.of(year, month, day)
                            val weekday = c - header.first + 1
                            if (weekday !in 1..7 || date.dayOfWeek.value != weekday) throw ImportException("课表日期与星期不一致")
                            val candidate = date.minusDays(((week - 1) * 7 + weekday - 1).toLong())
                            if (candidate.dayOfWeek != DayOfWeek.MONDAY || (monday != null && monday != candidate)) {
                                throw ImportException("课表各周日期不一致，未导入任何课程")
                            }
                            monday = candidate
                            if (lastDate?.let { date > it } != false) lastDate = date
                            c to (date to week)
                        }
                        continue
                    }
                    if (!LESSON_ROW.matches(text(r, 0).trim())) continue
                    for (c in cols) {
                        val value = text(r, c).trim('\n')
                        if (value.isBlank()) continue
                        val lines = value.split('\n')
                        val times = lines.indices.filter { SECTION.matches(lines[it].trim()) }
                        if (times.isEmpty()) throw ImportException("第 ${r + 1} 行课程缺少节次，未导入任何课程")
                        val fieldPositions = times.flatMap { listOf(it - 1, it + 1) }.toSet()
                        val courseHeaders = lines.indices.filter { it !in fieldPositions && COURSE.matches(lines[it].trim()) }
                        if (courseHeaders != times.map { it - 2 }) {
                            throw ImportException("第 ${r + 1} 行含有不完整课程，未导入任何课程")
                        }
                        times.forEachIndexed { index, i ->
                            if (i < 2) throw ImportException("第 ${r + 1} 行课程信息不完整")
                            val course = COURSE.matchEntire(lines[i - 2].trim())
                                ?: throw ImportException("第 ${r + 1} 行无法识别课程名称")
                            val time = SECTION.matchEntire(lines[i].trim())!!
                            val week = time.groupValues[1].toInt()
                            val sections = time.groupValues[2].chunked(2).map { it.toInt() }
                            if (sections.any { it !in 1..14 } || sections != sections.distinct().sorted()) throw ImportException("课程节次异常")
                            val (date, headerWeek) = dates[c] ?: throw ImportException("课程缺少对应日期")
                            if (week != headerWeek) throw ImportException("课程周次与日期不一致")
                            val next = if (index + 1 < times.size) times[index + 1] - 2 else lines.size
                            lessons += Lesson(
                                name = course.groupValues[1].trim(), kind = course.groupValues[2],
                                teachers = lines[i - 1].trim(), location = lines.getOrNull(i + 1).orEmpty().trim(),
                                content = lines.subList(minOf(i + 2, lines.size), next.coerceAtLeast(minOf(i + 2, lines.size))).joinToString("\n").trim(),
                                date = date.toString(), week = week, sections = sections
                            )
                        }
                    }
                }
                if (lessons.isEmpty() || monday == null || lastDate == null) {
                    throw ImportException("导出文件没有可验证的课程；为避免误清空，原课表未被替换")
                }
                ScheduleSnapshot(
                    termCode = "$startYear" + "0$semester", termLabel = "$startYear-$endYear 第${semester}学期",
                    weekOneMonday = monday.toString(), retrievedAt = now.toString(),
                    lessons = lessons.groupBy { it.copy(location = "") }.values.map { group ->
                        group.first().copy(location = group.map { it.location }.filter { it.isNotBlank() }.distinct().sorted().joinToString(" / "))
                    }.sortedWith(compareBy({ it.date }, { it.sections.first() }, { it.name })),
                    calendarEndDate = lastDate.toString()
                )
            }
        } catch (e: ImportException) { throw e }
        catch (e: Exception) { throw ImportException("无法完整解析课表，请重新从教务系统导出 XLS", e) }
    }

    private companion object {
        val TERM = Regex("(20\\d{2})\\s*-\\s*(20\\d{2})\\s*-\\s*([12])\\s*课表")
        val DATE_HEADER = Regex("(\\d{2})-(\\d{2})")
        val WEEK_HEADER = Regex("第(\\d+)周")
        val LESSON_ROW = Regex("第[一二三四五六七八九十0-9]+大?节")
        val SECTION = Regex("(\\d{1,2})-((?:\\d{2})+)")
        val COURSE = Regex("(.+)\\[([^]\\n]+)]")
    }
}
