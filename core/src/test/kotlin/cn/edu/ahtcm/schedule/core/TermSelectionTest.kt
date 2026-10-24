package cn.edu.ahtcm.schedule.core

import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.*

class TermSelectionTest {
    private val historic = AcademicTerm("202501", "2025-2026-1")
    private val current = AcademicTerm("202601", "2026-2027-1")

    @Test fun `canceled timetable commit preserves disk and memory and removes pending file`() {
        val dir = Files.createTempDirectory("schedule-canceled-commit")
        try {
            val repository = ScheduleRepository(dir)
            assertIs<UpdateResult.Success>(repository.importXls(xls(historic)))
            val before = repository.snapshot()
            val disk = Files.readAllBytes(dir.resolve("timetable.json"))
            var checks = 0
            assertFailsWith<CancellationException> {
                repository.synchronizeSchool(beforeCommit = {
                    checks++
                    if (checks == 2) throw CancellationException("canceled commit")
                }) { SchoolTimetable(xls(current), (1..14).map { ClassPeriod(it, "08:30", "09:10") }) }
            }
            assertEquals(2, checks)
            assertEquals(before, repository.snapshot())
            assertContentEquals(disk, Files.readAllBytes(dir.resolve("timetable.json")))
            Files.list(dir).use { files -> assertEquals(listOf("timetable.json"), files.map { it.fileName.toString() }.toList()) }
            var fetched = false
            assertFailsWith<CancellationException> {
                repository.synchronizeSchool(beforeCommit = { throw CancellationException("canceled entry") }) {
                    fetched = true
                    error("Canceled operation must not fetch")
                }
            }
            assertFalse(fetched)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `canceled semester selection preserves disk and memory and removes pending file`() {
        val dir = Files.createTempDirectory("selection-canceled-commit")
        try {
            val store = TermSelectionStore(dir)
            store.select(historic)
            val disk = Files.readAllBytes(dir.resolve("selected-term.json"))
            var checks = 0
            assertFailsWith<CancellationException> {
                store.select(current) {
                    checks++
                    if (checks == 2) throw CancellationException("canceled commit")
                }
            }
            assertEquals(2, checks)
            assertEquals(historic, store.selected())
            assertContentEquals(disk, Files.readAllBytes(dir.resolve("selected-term.json")))
            Files.list(dir).use { files -> assertEquals(listOf("selected-term.json"), files.map { it.fileName.toString() }.toList()) }
            assertFailsWith<CancellationException> { store.select(current) { throw CancellationException("canceled entry") } }
            assertEquals(historic, store.selected())
            assertContentEquals(disk, Files.readAllBytes(dir.resolve("selected-term.json")))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `canceled school fetch propagates cancellation without replacing cache`() {
        val dir = Files.createTempDirectory("schedule-canceled-fetch")
        try {
            val repository = ScheduleRepository(dir)
            assertIs<UpdateResult.Success>(repository.importXls(xls(historic)))
            val before = repository.snapshot()
            assertFailsWith<CancellationException> {
                repository.synchronizeSchool { throw CancellationException("canceled fetch") }
            }
            assertEquals(before, repository.snapshot())
            assertEquals(before, ScheduleRepository(dir).snapshot())
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `selected semester persists separately for each account`() {
        val dir = Files.createTempDirectory("term-accounts")
        try {
            val accounts = AccountStorage(dir)
            accounts.select("anonymous-a")
            assertNull(accounts.termSelection.selected())
            accounts.termSelection.select(historic)
            accounts.select("anonymous-b")
            assertNull(accounts.termSelection.selected())
            accounts.termSelection.select(current)
            assertEquals(current, AccountStorage(dir).termSelection.selected())
            accounts.select("anonymous-a")
            assertEquals(historic, accounts.termSelection.selected())
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `corrupt selection is reported and failed persistence preserves memory`() {
        val dir = Files.createTempDirectory("term-failure")
        try {
            val file = dir.resolve("selected-term.json")
            Files.writeString(file, "{broken}")
            val store = TermSelectionStore(dir)
            assertNull(store.selected())
            assertNotNull(store.loadError)
            store.select(historic)
            assertNull(store.loadError)
            assertEquals(historic, TermSelectionStore(dir).selected())
            Files.delete(file)
            Files.createDirectory(file)
            Files.writeString(file.resolve("block"), "prevent replacement")
            assertFails { store.select(current) }
            assertEquals(historic, store.selected())
            Files.list(dir).use { files -> assertEquals(listOf("selected-term.json"), files.map { it.fileName.toString() }.toList()) }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `repository retrieves each cached semester without changing active snapshot`() {
        val dir = Files.createTempDirectory("term-snapshots")
        try {
            val repository = ScheduleRepository(dir)
            assertIs<UpdateResult.Success>(repository.importXls(xls(historic)))
            assertIs<UpdateResult.Success>(repository.importXls(xls(current)))
            val restored = ScheduleRepository(dir)
            assertEquals("202501", restored.snapshot("202501")!!.termCode)
            assertEquals("202601", restored.snapshot()!!.termCode)
            assertEquals(listOf("202601", "202501"), restored.snapshots().map { it.termCode })
            assertNull(restored.snapshot("202502"))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `historic download initializes main page and exports requested semester`() {
        var initialized = false
        val client = client(historic, historic) { request ->
            if (URI(request.url).path.endsWith("main.page")) initialized = true
            if (URI(request.url).path.endsWith("week.page")) {
                check(initialized)
                assertEquals("xnxqdm=202501", URI(request.url).rawQuery)
            }
            if (URI(request.url).path.endsWith("prtxskb")) {
                assertTrue(URI(request.url).rawQuery.startsWith("xnxqdm=202501&xnxqmc=2025-2026-1&zc=&"))
            }
        }
        val result = client.downloadSchoolTimetable(historic)
        assertEquals("202501", XlsScheduleImporter().parse(result.xls).termCode)
        assertEquals(14, result.periods.size)
    }

    @Test fun `requested semester rejects different selected school semester`() {
        assertFailsWith<SchoolException> { client(current, historic).downloadSchoolTimetable(historic) }
    }

    @Test fun `requested semester rejects different selected school label`() {
        assertFailsWith<SchoolException> { client(historic, historic, label = "2026-2027-1").downloadSchoolTimetable(historic) }
    }

    @Test fun `requested semester rejects export for different semester`() {
        assertFailsWith<SchoolException> { client(historic, current).downloadSchoolTimetable(historic) }
    }

    private fun client(selected: AcademicTerm, exported: AcademicTerm, label: String = selected.label, inspect: (SchoolRequest) -> Unit = {}): SchoolClient =
        SchoolClient(SchoolTransport { request ->
            inspect(request)
            val body = when (URI(request.url).path) {
                "/new/student/xsgrkb/main.page" -> "<script>url: '/new/student/xsgrkb/week.page?xnxqdm=202601'</script>"
                "/new/student/xsgrkb/week.page" -> {
                    val periods = (1..14).joinToString(",") { """{"jcdm":"${it.toString().padStart(2, '0')}","qssj":"08:30:00","jssj":"09:10:00"}""" }
                    "<select id='xnxqdm'><option value='${selected.code}' selected>$label</option></select><script>var businessHours = $.parseJSON('[$periods]');</script>"
                }
                "/new/printkb/prtxskb" -> return@SchoolTransport SchoolResponse(200, emptyMap(), xls(exported))
                else -> error("Unexpected request")
            }
            SchoolResponse(200, emptyMap(), body.toByteArray())
        })

    private fun xls(term: AcademicTerm): ByteArray = org.apache.poi.hssf.usermodel.HSSFWorkbook().use { book ->
        val sheet = book.createSheet("Sheet1")
        fun cell(r: Int, c: Int, value: String) { (sheet.getRow(r) ?: sheet.createRow(r)).createCell(c).setCellValue(value) }
        cell(0, 0, "匿名${term.label}课表")
        cell(1, 0, "周次\n节次"); cell(1, 1, "第8周")
        cell(2, 1, if (term.code == "202501") "10-20" else "10-19")
        cell(2, 3, if (term.code == "202501") "10-22" else "10-21")
        cell(3, 0, "星期"); cell(10, 0, "第七大节")
        cell(10, 3, "护理实验[实验实训]\n示例教师\n8-1314\n实训室4\n基础护理实训")
        java.io.ByteArrayOutputStream().also { book.write(it) }.toByteArray()
    }
}
