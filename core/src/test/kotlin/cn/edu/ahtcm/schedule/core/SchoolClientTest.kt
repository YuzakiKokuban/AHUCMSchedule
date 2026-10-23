package cn.edu.ahtcm.schedule.core

import java.net.URI
import kotlin.test.*

class SchoolClientTest {
    @Test fun `school login encoder matches official script including unicode and partial blocks`() {
        assertEquals("39644174795FB4D0", SsoEncoder.encode("abc"))
        assertEquals("81350AB5F6561329C1201E06BA5BBB37", SsoEncoder.encode("中文demo"))
        assertEquals("D8D35E5019288C41D7FB43AA9BAD9353A5C783FF831356A39A6B87FE327C3EE9C05209A71D3D4089D87CA02458B384924213B2D5091AF03F3257A6CBC062E630", SsoEncoder.encode("testaccountpasswordLT-EXAMPLE"))
    }

    @Test fun `login initializes menu and export always requests full current term`() {
        val requests = mutableListOf<SchoolRequest>()
        val xls = sampleXls()
        val client = SchoolClient(SchoolTransport { req ->
            requests += req
            when (URI(req.url).path) {
                "/tpass/login" -> if (req.method == "GET") html("""<form id='loginForm' action='/tpass/login'><input name='lt' value='LT-demo'><input name='execution' value='e1s1'><input name='_eventId' value='submit'></form>""") else SchoolResponse(302, mapOf("Location" to listOf("https://jwxt.ahtcm.edu.cn/new/ssoLogin?ticket=demo")), byteArrayOf())
                "/new/ssoLogin" -> html("<title>教学管理系统</title>")
                "/new/desktop/getMenu" -> html("""[{"menuname":"课表查询","menuurl":"new/student/xsgrkb/main.page"}]""")
                "/new/student/xsgrkb/main.page" -> html("""<script>url: '/new/student/xsgrkb/week.page?xnxqdm=202601'</script>""")
                "/new/student/xsgrkb/week.page" -> html("""<select id='xnxqdm'><option value='202602'>2026-2027-2</option><option value='202601' selected>2026-2027-1</option></select>""")
                "/new/printkb/prtxskb" -> SchoolResponse(201, emptyMap(), xls)
                else -> error("Unexpected request ${URI(req.url).path}")
            }
        })
        client.login("anonymous", "example-password")
        assertContentEquals(xls, client.downloadTimetable())
        val export = URI(requests.last().url).rawQuery
        assertTrue(export.contains("xnxqdm=202601"))
        assertTrue(export.contains("zc=&"))
        assertFalse(requests.any { it.url.contains("doRead") || it.url.contains("doClear") })
        assertFalse(requests.any { it.body.orEmpty().contains("example-password") })
        assertEquals("POST", requests.first { URI(it.url).path == "/new/desktop/getMenu" }.method)
    }

    @Test fun `redirect cannot send credentials or session to another site`() {
        var calls = 0
        val client = SchoolClient(SchoolTransport {
            calls++
            SchoolResponse(302, mapOf("Location" to listOf("https://example.org/steal")), byteArrayOf())
        })
        assertFailsWith<SchoolException> { client.login("anonymous", "example-password") }
        assertEquals(1, calls)
    }

    @Test fun `message pagination includes read and unread without marking either read`() {
        val paths = mutableListOf<String>()
        val client = SchoolClient(SchoolTransport { req ->
            val path = URI(req.url).path
            paths += path
            when (path) {
                "/new/notice/notice.page" -> html("<title>通知</title>")
                "/new/notice/getNotRead" -> html("""{"total":2,"rows":[{"id":"one","msg":"调课通知一","cjsj":"2026-10-21 10:00:00"},{"id":"two","msg":"调课通知二","cjsj":"2026-10-21 11:00:00"}]}""")
                "/new/notice/getRead" -> html("""{"total":1,"rows":[{"id":"old","msg":"停课通知","cjsj":"2026-10-20 10:00:00"}]}""")
                else -> error("Unexpected request")
            }
        })
        val result = client.fetchNotices()
        assertEquals(setOf("one", "two", "old"), result.map { it.id }.toSet())
        assertTrue(paths.all { it in setOf("/new/notice/notice.page", "/new/notice/getNotRead", "/new/notice/getRead") })
    }

    @Test fun `school period times are validated and persisted together with the timetable`() {
        val periods = (1..14).map { ClassPeriod(it, "08:30", "09:10") }
        val directory = java.nio.file.Files.createTempDirectory("ahu-periods")
        try {
            val repository = ScheduleRepository(directory)
            assertIs<UpdateResult.Success>(repository.synchronizeSchool { SchoolTimetable(sampleXls(), periods) })
            assertEquals(periods, ScheduleRepository(directory).snapshot()!!.periods)
            assertIs<UpdateResult.Failure>(repository.synchronizeSchool { SchoolTimetable(sampleXls(), listOf(ClassPeriod(1, "25:00", "09:10"))) })
            assertEquals(periods, repository.snapshot()!!.periods)
            assertIs<UpdateResult.Success>(repository.importXls(sampleXls()))
            val imported = ScheduleRepository(directory).snapshot()!!
            assertEquals(periods, imported.periods)
            assertEquals("file", imported.source)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `period parser uses official clock values and rejects missing sections`() {
        val data = (1..14).joinToString(",") { """{"jcdm":"${it.toString().padStart(2, '0')}","qssj":"08:30:00","jssj":"09:10:00"}""" }
        val periods = SchoolPeriodParser.parse("<script>var businessHours = $.parseJSON('[$data]');</script>")
        assertEquals(ClassPeriod(14, "08:30", "09:10"), periods.last())
        assertFailsWith<SchoolException> { SchoolPeriodParser.parse("<script>var businessHours = $.parseJSON('[]');</script>") }
    }

    @Test fun `verified browser cookies restore session without resending password`() {
        var installed: Map<String,String> = emptyMap()
        val paths = mutableListOf<String>()
        val client = SchoolClient(object : SchoolTransport {
            override fun installSessionCookies(cookies:Map<String,String>) { installed=cookies }
            override fun exchange(request:SchoolRequest):SchoolResponse {
                val path=URI(request.url).path;paths+=path
                return html(if(path.endsWith("getMenu")) "xsgrkb/main.page" else "/new/student/xsgrkb/week.page?xnxqdm=202601")
            }
        })
        client.resumeSession(SchoolWebSession("anonymous",mapOf("https://jwxt.ahtcm.edu.cn" to "SESSION=anonymous-cookie"),true))
        assertTrue(client.isAuthenticated)
        assertEquals("SESSION=anonymous-cookie",installed["https://jwxt.ahtcm.edu.cn"])
        assertTrue(paths.none { it.contains("tpass/login") })
        assertFails { client.resumeSession(SchoolWebSession("anonymous",mapOf("example.org" to "SECRET=bad"),true)) }
        assertFalse(client.isAuthenticated)
        assertFails { client.resumeSession(SchoolWebSession("arbitrary",installed)) }
        assertFalse(client.isAuthenticated)
    }

    private fun html(body: String) = SchoolResponse(200, emptyMap(), body.toByteArray())

    private fun sampleXls(): ByteArray = org.apache.poi.hssf.usermodel.HSSFWorkbook().use { book ->
        val sheet = book.createSheet("Sheet1")
        fun cell(r: Int, c: Int, value: String) { (sheet.getRow(r) ?: sheet.createRow(r)).createCell(c).setCellValue(value) }
        cell(0, 0, "匿名2026-2027-1课表")
        cell(1, 0, "周次\n节次"); cell(1, 1, "第8周")
        cell(2, 1, "10-19"); cell(2, 3, "10-21")
        cell(3, 0, "星期"); cell(10, 0, "第七大节")
        cell(10, 3, "护理实验[实验实训]\n示例教师\n8-1314\n实训室4\n基础护理实训")
        java.io.ByteArrayOutputStream().also { book.write(it) }.toByteArray()
    }
}
