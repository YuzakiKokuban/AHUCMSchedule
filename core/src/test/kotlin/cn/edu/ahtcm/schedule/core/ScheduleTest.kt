package cn.edu.ahtcm.schedule.core

import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.util.CellRangeAddress

class ScheduleTest {
    @Test fun `reading cached timetable never waits for a blocked network refresh`() {
        val dir = Files.createTempDirectory("schedule-nonblocking")
        val threads = java.util.concurrent.Executors.newFixedThreadPool(2)
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            val repository = ScheduleRepository(dir)
            repository.importXls(workbook())
            val before = repository.snapshot()
            val refresh = threads.submit<UpdateResult> { repository.synchronize {
                started.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                workbook(lesson = "最新解剖学")
            } }
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val read = threads.submit<ScheduleSnapshot?> { repository.snapshot() }
            assertEquals(before, read.get(1, java.util.concurrent.TimeUnit.SECONDS))
            release.countDown()
            assertTrue(refresh.get(5, java.util.concurrent.TimeUnit.SECONDS) is UpdateResult.Success)
        } finally {
            release.countDown()
            threads.shutdownNow()
            threads.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
            dir.toFile().deleteRecursively()
        }
    }
    // An importer that counts both occupied rows would duplicate this four-period class.
    @Test fun `xls imports dates full fields and merges repeated four period classes`() {
        val snapshot = XlsScheduleImporter().parse(workbook(), Instant.parse("2026-10-21T10:00:00Z"))
        assertEquals("202601", snapshot.termCode)
        assertEquals("2026-08-31", snapshot.weekOneMonday)
        assertEquals(2, snapshot.lessons.size)
        val lab = snapshot.lessons.first { it.name == "护理实验" }
        assertEquals(listOf(5, 6, 7, 8), lab.sections)
        assertEquals("2026-10-21", lab.date)
        assertEquals("张老师,李老师", lab.teachers)
        assertEquals("实训室4", lab.location)
        assertEquals("四肢骨、颅骨", lab.content)
        assertEquals(listOf(13, 14), snapshot.lessons.first { it.name == "解剖学" }.sections)
    }

    // A missing or mismatched date must fail the entire import, not erase a day silently.
    @Test fun `rejects inconsistent week dates and arbitrary files`() {
        assertFailsWith<ImportException> { XlsScheduleImporter().parse(workbook(week = 9)) }
        assertFailsWith<ImportException> { XlsScheduleImporter().parse("<html>请登录</html>".toByteArray()) }
        assertFailsWith<ImportException> { XlsScheduleImporter().parse(workbook(broken = true)) }
    }

    // The next academic year's January belongs to the previous autumn term.
    @Test fun `cross year dates resolve using academic term`() {
        val snapshot = XlsScheduleImporter().parse(workbook(monthDay = "01-06", week = 19))
        assertEquals("2027-01-06", snapshot.lessons.first().date)
        assertEquals("2026-08-31", snapshot.weekOneMonday)
    }

    @Test fun `parallel classrooms for same lesson display once without losing locations`() {
        val snapshot = XlsScheduleImporter().parse(workbook(multipleRooms = true))
        assertEquals(2, snapshot.lessons.size)
        assertEquals("实训室4 / 实训室5", snapshot.lessons.first { it.name == "护理实验" }.location)
    }

    // A failed network response, invalid XLS or disk failure must leave the old snapshot intact.
    @Test fun `sync only replaces snapshot after successful complete validation`() {
        val dir = Files.createTempDirectory("schedule-test")
        try {
            val repository = ScheduleRepository(dir)
            assertTrue(repository.importXls(workbook()) is UpdateResult.Success)
            val before = repository.snapshot()
            assertTrue(repository.synchronize { error("network disconnected") } is UpdateResult.Failure)
            assertEquals(before, repository.snapshot())
            assertTrue(repository.importXls("bad".toByteArray()) is UpdateResult.Failure)
            assertEquals(before, ScheduleRepository(dir).snapshot())
            assertTrue(repository.importXls(workbook(title = "2026-2027-1课表", lesson = "最新解剖学")) is UpdateResult.Success)
            assertEquals(2, repository.snapshot()!!.lessons.size)
            assertTrue(repository.snapshot()!!.lessons.any { it.name == "最新解剖学" })
            assertFalse(repository.snapshot()!!.lessons.any { it.name == "解剖学" })
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `failed empty export never overwrites a populated term`() {
        val dir = Files.createTempDirectory("schedule-empty")
        try {
            val repository = ScheduleRepository(dir)
            assertTrue(repository.importXls(workbook()) is UpdateResult.Success)
            val before = repository.snapshot()
            assertTrue(repository.importXls(workbook(empty = true)) is UpdateResult.Failure)
            assertEquals(before, repository.snapshot())
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `malformed second course in a shared cell cannot partially replace cached timetable`() {
        val dir = Files.createTempDirectory("schedule-partial")
        try {
            val repository = ScheduleRepository(dir)
            assertTrue(repository.importXls(workbook()) is UpdateResult.Success)
            val before = repository.snapshot()
            assertTrue(repository.importXls(workbook(mixedMalformed = true)) is UpdateResult.Failure)
            assertEquals(before, repository.snapshot())
            assertEquals(before, ScheduleRepository(dir).snapshot())
        } finally { dir.toFile().deleteRecursively() }
    }

    // Read and unread records with the same content are one display item, never timetable commands.
    @Test fun `notifications deduplicate independently without changing timetable`() {
        val text = "原定于 2026-10-23 05,06,07,08节 张老师的《护理实验》课程[经络实训]，现已调课。现调整为2026-11-27 05,06,07,08节 张老师。授课内容：经络实训"
        val notices = NoticeParser().parse(listOf(
            RawNotice("1", text, "2026-10-20 17:44:32"),
            RawNotice("2", text, "2026-10-20 17:44:31"),
            RawNotice("3", "教务系统维护通知", "2026-10-20 10:00:00")
        ))
        assertEquals(1, notices.size)
        assertEquals(2, notices.single().duplicateCount)
        assertEquals("2026-10-23", notices.single().originalDate)
        assertEquals("2026-11-27", notices.single().newDate)
        assertEquals(text, notices.single().text)
    }

    @Test fun `unknown adjustment format remains visible without guessed dates`() {
        val notices = NoticeParser().parse(listOf(RawNotice("4", "护理学课程调课，请查看教务最新课表", "2026-10-21 10:00:00")))
        assertEquals(1, notices.size)
        assertNull(notices.single().newDate)
    }

    @Test fun `a failed durable write keeps the previous in memory snapshot`() {
        val dir = Files.createTempDirectory("schedule-write")
        val moved = dir.resolveSibling(dir.fileName.toString() + "-moved")
        try {
            val repository = ScheduleRepository(dir)
            assertTrue(repository.importXls(workbook()) is UpdateResult.Success)
            val before = repository.snapshot()
            Files.move(dir, moved)
            Files.writeString(dir, "not a directory")
            assertTrue(repository.importXls(workbook(lesson = "不能保存的新课")) is UpdateResult.Failure)
            assertEquals(before, repository.snapshot())
        } finally { dir.toFile().deleteRecursively(); moved.toFile().deleteRecursively() }
    }

    @Test fun `official local sample matches manually checked October 21 timetable`() {
        val path = System.getenv("AHUCM_SAMPLE_XLS")
        org.junit.jupiter.api.Assumptions.assumeTrue(path != null, "Private sample supplied locally only")
        val snapshot = XlsScheduleImporter().parse(Files.readAllBytes(java.nio.file.Path.of(path)))
        assertEquals("202601", snapshot.termCode)
        assertEquals("2026-08-31", snapshot.weekOneMonday)
        assertEquals(193, snapshot.lessons.size)
        val lessons = snapshot.lessons.filter { it.date == "2026-10-21" }.sortedBy { it.sections.first() }
        assertEquals(listOf("计算机与人工智能基础", "正常人体解剖学实验", "正常人体解剖学", "正常人体解剖学"), lessons.map { it.name })
        assertEquals(listOf(listOf(5, 6), listOf(7, 8), listOf(11, 12), listOf(13, 14)), lessons.map { it.sections })
    }

    private fun workbook(
        title: String = "示例学生2026-2027-1课表", monthDay: String = "10-21", week: Int = 8,
        broken: Boolean = false, lesson: String = "解剖学", empty: Boolean = false,
        multipleRooms: Boolean = false, mixedMalformed: Boolean = false
    ): ByteArray = HSSFWorkbook().use { book ->
        val s = book.createSheet("Sheet1")
        fun cell(r: Int, c: Int, value: String) { (s.getRow(r) ?: s.createRow(r)).createCell(c).setCellValue(value) }
        cell(0, 0, title)
        val sampleDate = LocalDate.parse("${if (monthDay.startsWith("01")) 2027 else 2026}-$monthDay")
        val headerWeek = java.time.temporal.ChronoUnit.WEEKS.between(LocalDate.parse("2026-08-31"), sampleDate.minusDays(2)) + 1
        cell(1, 0, "周次\n节次"); cell(1, 1, "第${headerWeek}周")
        cell(2, 1, sampleDate.minusDays(2).toString().substring(5))
        cell(2, 3, monthDay)
        cell(3, 0, "星期"); cell(3, 1, "一"); cell(3, 3, "三")
        if (!empty) {
            val lab = "\r\n护理实验[实验实训]\r\n张老师,李老师\r\n$week-05060708\r\n实训室4\r\n四肢骨、颅骨"
            val labs = when {
                mixedMalformed -> lab + "\n课程B[理论课]\n示例教师\n8-XX\n示例教室"
                multipleRooms -> lab + lab.replace("实训室4", "实训室5")
                else -> lab
            }
            cell(6, 0, "第三大节"); cell(6, 3, labs)
            cell(7, 0, "第四大节"); cell(7, 3, labs)
            s.addMergedRegion(CellRangeAddress(6, 7, 3, 3))
            cell(10, 0, "第七大节")
            cell(10, 3, if (broken) "不完整课程[理论课]\n第八周未知节次" else "\n$lesson[理论课]\n尹老师\n$week-1314\n教室402\n肌学")
        }
        java.io.ByteArrayOutputStream().also { book.write(it) }.toByteArray()
    }
}
