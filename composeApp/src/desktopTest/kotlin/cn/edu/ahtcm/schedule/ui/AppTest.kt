package cn.edu.ahtcm.schedule.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asSkiaBitmap
import cn.edu.ahtcm.schedule.core.ScheduleRepository
import java.nio.file.Files
import java.time.LocalDate
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Rule
import org.junit.Test

class AppTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `module area follows activation and hides again on service loss`() {
        val directory = Files.createTempDirectory("ahu-module-active")
        try {
            val active = kotlinx.coroutines.flow.MutableStateFlow(false)
            val actions = object : PlatformActions {
                override val repository = ScheduleRepository(directory).apply { importXls(sample()) }
                override val moduleSupported = true
                override val moduleActivation = active
                override val moduleLinked = true
                override val moduleStatus = kotlinx.coroutines.flow.MutableStateFlow("framework connected")
                override fun openSchool() = Unit
                override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) = Unit
            }
            compose.setContent { App(actions, LocalDate.parse("2026-10-21")) }
            compose.onNodeWithText("连接教务").performClick()
            compose.onNodeWithText("开启小爱课表自动管理").assertDoesNotExist()
            active.value = true
            compose.waitForIdle()
            compose.onNodeWithText("开启小爱课表自动管理").assertExists()
            compose.onNodeWithText("停止小爱自动管理").assertExists()
            compose.onNodeWithText("framework connected").assertExists()
            active.value = false
            compose.waitForIdle()
            compose.onNodeWithText("开启小爱课表自动管理").assertDoesNotExist()
            compose.onNodeWithText("停止小爱自动管理").assertDoesNotExist()
            compose.onNodeWithText("framework connected").assertDoesNotExist()
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `module area stays hidden without framework activation despite saved management state`() {
        val directory = Files.createTempDirectory("ahu-module-hidden")
        try {
            val actions = object : PlatformActions {
                override val repository = ScheduleRepository(directory).apply { importXls(sample()) }
                override val moduleSupported = true
                override val moduleLinked = true
                override val moduleStatus = kotlinx.coroutines.flow.MutableStateFlow("saved module status")
                override fun openSchool() = Unit
                override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) = Unit
            }
            compose.setContent { App(actions, LocalDate.parse("2026-10-21")) }
            compose.onNodeWithText("连接教务").performClick()
            compose.onNodeWithText("开启小爱课表自动管理").assertDoesNotExist()
            compose.onNodeWithText("停止小爱自动管理").assertDoesNotExist()
            compose.onNodeWithText("saved module status").assertDoesNotExist()
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `login synchronizes official timetable and displays adjustment feed`() {
        val directory = Files.createTempDirectory("ahu-online-ui")
        try {
            val requests = mutableListOf<String>()
            val foregroundState = kotlinx.coroutines.flow.MutableStateFlow(true)
            val blockExport = java.util.concurrent.atomic.AtomicBoolean(false)
            val exportStarted = java.util.concurrent.CountDownLatch(1)
            val releaseExport = java.util.concurrent.CountDownLatch(1)
            val client = cn.edu.ahtcm.schedule.core.SchoolClient(cn.edu.ahtcm.schedule.core.SchoolTransport { req ->
                val path = java.net.URI(req.url).path
                synchronized(requests) { requests += path }
                fun html(text: String) = cn.edu.ahtcm.schedule.core.SchoolResponse(200, emptyMap(), text.toByteArray())
                when (path) {
                    "/tpass/login" -> if (req.method == "GET") html("<form id='loginForm' action='/tpass/login'><input name='lt' value='LT-demo'><input name='execution' value='e1s1'></form>") else html("<title>教学管理系统</title>")
                    "/new/desktop/getMenu" -> html("[\"new/student/xsgrkb/main.page\"]")
                    "/new/student/xsgrkb/main.page" -> html("/new/student/xsgrkb/week.page?xnxqdm=202601")
                    "/new/student/xsgrkb/week.page" -> html("<select id='xnxqdm'><option value='202601' selected>2026-2027-1</option></select><script>var businessHours = $.parseJSON('[${(1..14).joinToString(",") { "{\"jcdm\":\"$it\",\"qssj\":\"08:30:00\",\"jssj\":\"09:10:00\"}" }}]');</script>")
                    "/new/printkb/prtxskb" -> {
                        if (blockExport.get()) {
                            exportStarted.countDown()
                            check(releaseExport.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        }
                        cn.edu.ahtcm.schedule.core.SchoolResponse(201, emptyMap(), sample(lesson = if (blockExport.get()) "更新后的护理实验" else "护理实验"))
                    }
                    "/new/notice/notice.page" -> html("<title>通知</title>")
                    "/new/notice/getNotRead" -> html("""{"total":1,"rows":[{"id":"1","msg":"护理实验调课，请以最新课表为准","cjsj":"2026-10-21 12:00:00"}]}""")
                    "/new/notice/getRead" -> html("""{"total":0,"rows":[]}""")
                    else -> error("Unexpected request")
                }
            })
            val actions = object : PlatformActions {
                override val credentials = object : cn.edu.ahtcm.schedule.core.CredentialStore {
                    var saved: cn.edu.ahtcm.schedule.core.SavedLogin? = cn.edu.ahtcm.schedule.core.SavedLogin("anonymous", "example-password")
                    override fun load() = saved
                    override fun save(login: cn.edu.ahtcm.schedule.core.SavedLogin) { saved = login }
                    override fun clear() { saved = null }
                }
                override val repository = ScheduleRepository(directory)
                override val noticeStore = cn.edu.ahtcm.schedule.core.NoticeStore(directory)
                override val school = client
                override val foreground = foregroundState
                override fun openSchool() = Unit
                override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) = Unit
            }
            compose.setContent { App(actions, LocalDate.parse("2026-10-21")) }
            compose.onNodeWithContentDescription("同步").assertExists()
            compose.waitUntil(10000) { actions.repository.snapshot()?.source == "school" && actions.noticeStore.snapshot() != null }
            compose.onNodeWithText("我的课表").performClick()
            compose.onNodeWithText("护理实验").assertExists()
            compose.onNodeWithText("调课通知").performClick()
            compose.onNodeWithText("护理实验调课，请以最新课表为准").assertExists()
            // A blocked export must still publish its completed snapshot when the
            // app goes into the background, and be visible on return.
            foregroundState.value = false
            compose.waitForIdle()
            blockExport.set(true)
            foregroundState.value = true
            compose.waitUntil(10000) { exportStarted.count == 0L }
            foregroundState.value = false
            compose.waitForIdle()
            foregroundState.value = true
            compose.waitForIdle()
            compose.onNodeWithText("连接教务").performClick()
            compose.onNodeWithText("从教务系统获取课表").assertExists()
            releaseExport.countDown()
            compose.waitUntil(10000) { actions.repository.snapshot()?.lessons?.single()?.name == "更新后的护理实验" }
            foregroundState.value = true
            compose.onNodeWithText("我的课表").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("更新后的护理实验").fetchSemanticsNodes().isNotEmpty() }
            kotlin.test.assertFalse(requests.any { it.contains("doRead") || it.contains("doClear") })
        } finally { directory.toFile().deleteRecursively() }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun `import displays actual course and next week does not retain it`() {
        val directory = Files.createTempDirectory("ahu-ui")
        try {
            val actions = object : PlatformActions {
                override val repository = ScheduleRepository(directory)
                override fun openSchool() = Unit
                override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) { onResult(sample(), null) }
            }
            compose.setContent { App(actions, LocalDate.parse("2026-10-21")) }
            compose.onNodeWithText("连接教务").performClick()
            compose.onNodeWithText("导入官方 XLS 文件").performClick()
            compose.onNodeWithText("我的课表").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("护理实验").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("实训室4").assertExists()
            compose.onNodeWithText("13").assertExists()
            compose.onNodeWithText("周三").assertExists()
            val image = org.jetbrains.skia.Image.makeFromBitmap(compose.onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!
            val output = java.nio.file.Path.of("build", "preview-desktop.png")
            Files.createDirectories(output.parent)
            Files.write(output, image.bytes)
            compose.onNodeWithContentDescription("下一周").performClick()
            compose.onNodeWithText("护理实验").assertDoesNotExist()
        } finally { directory.toFile().deleteRecursively() }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun `phone shows a weekly grid with section times and opens full course details`() {
        val directory = Files.createTempDirectory("ahu-phone")
        try {
            val actions = object : PlatformActions {
                override val repository = ScheduleRepository(directory).apply { synchronizeSchool { cn.edu.ahtcm.schedule.core.SchoolTimetable(sample(daytime = true), samplePeriods()) } }
                override fun openSchool() = Unit
                override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) = Unit
            }
            compose.setContent {
                Box(Modifier.requiredSize(390.dp, 720.dp).testTag("phone")) {
                    App(actions, LocalDate.parse("2026-10-21"))
                }
            }
            compose.onNodeWithText("护理实验").assertExists()
            compose.onNodeWithText("08:30").assertIsDisplayed()
            compose.onNodeWithText("09:10").assertIsDisplayed()
            compose.onNodeWithText("基础护理学").assertIsDisplayed()
            compose.onNodeWithText("每一天，心中有数", substring = true).assertDoesNotExist()
            val image = org.jetbrains.skia.Image.makeFromBitmap(compose.onNodeWithTag("phone").captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!
            val output = java.nio.file.Path.of("build", "preview-phone.png")
            Files.createDirectories(output.parent)
            Files.write(output, image.bytes)
            compose.onNodeWithText("周一").assertExists()
            compose.onNodeWithText("周日").assertExists()
            compose.onNodeWithText("13").assertExists()
            compose.onNodeWithText("护理实验").performScrollTo().performClick()
            compose.onNodeWithText("基础护理实训").assertExists()
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `navigation animates the incoming page before settling`() {
        val directory = Files.createTempDirectory("ahu-motion")
        try {
            val actions = object : PlatformActions {
                override val repository = ScheduleRepository(directory)
                override fun openSchool() = Unit
                override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) = Unit
            }
            compose.setContent { Box(Modifier.requiredSize(390.dp, 720.dp)) { App(actions) } }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("连接教务").performClick()
            compose.mainClock.advanceTimeBy(64)
            val moving = compose.onNodeWithText("从教务系统获取课表").fetchSemanticsNode().boundsInRoot.left
            compose.mainClock.advanceTimeBy(400)
            val settled = compose.onNodeWithText("从教务系统获取课表").fetchSemanticsNode().boundsInRoot.left
            kotlin.test.assertTrue(moving > settled + 1f, "The page should slide into place over time")
            compose.mainClock.autoAdvance = true
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `week navigation slides the new dates into place`() {
        val directory = Files.createTempDirectory("ahu-week-motion")
        try {
            val actions = object : PlatformActions {
                override val repository = ScheduleRepository(directory).apply { importXls(sample()) }
                override fun openSchool() = Unit
                override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) = Unit
            }
            compose.setContent { Box(Modifier.requiredSize(390.dp,720.dp)) { App(actions, LocalDate.parse("2026-10-21")) } }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription("下一周").performClick()
            compose.mainClock.advanceTimeBy(64)
            val moving = compose.onNodeWithText("10/26").fetchSemanticsNode().boundsInRoot.left
            compose.mainClock.advanceTimeBy(400)
            val settled = compose.onNodeWithText("10/26").fetchSemanticsNode().boundsInRoot.left
            kotlin.test.assertTrue(moving > settled + 1f)
            compose.mainClock.autoAdvance = true
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `existing authenticated session still exposes saved login removal after recreation`() {
        val directory = Files.createTempDirectory("ahu-saved-state")
        try {
            val actions = object : PlatformActions {
                override val repository = ScheduleRepository(directory)
                override val sessionReady = true
                override val credentials = object : cn.edu.ahtcm.schedule.core.CredentialStore {
                    override fun load() = cn.edu.ahtcm.schedule.core.SavedLogin("anonymous","secret")
                    override fun save(login: cn.edu.ahtcm.schedule.core.SavedLogin) = Unit
                    override fun clear() = Unit
                }
                override fun openSchool() = Unit
                override fun chooseXls(onResult:(ByteArray?,String?)->Unit) = Unit
            }
            compose.setContent { App(actions) }
            compose.onNodeWithText("连接教务").performClick()
            compose.onNodeWithText("清除已保存登录").assertExists()
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `restored picker delivery imports without an in memory callback`() {
        val directory = Files.createTempDirectory("ahu-restored-picker")
        try {
            val delivery = kotlinx.coroutines.flow.MutableStateFlow<FileSelection?>(FileSelection(1, sample()))
            val actions = object : PlatformActions {
                override val repository = ScheduleRepository(directory)
                override val selections = delivery
                override fun consumeSelection(token: Long) { if (delivery.value?.token == token) delivery.value = null }
                override fun openSchool() = Unit
                override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) = error("No picker callback survives recreation")
            }
            compose.setContent { App(actions, LocalDate.parse("2026-10-21")) }
            compose.waitUntil(10000) { compose.onAllNodesWithText("护理实验").fetchSemanticsNodes().isNotEmpty() }
            kotlin.test.assertNotNull(ScheduleRepository(directory).snapshot())
            kotlin.test.assertNull(delivery.value)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `teaching content can expand beyond the card summary`() {
        val directory = Files.createTempDirectory("ahu-details")
        try {
            val actions = object : PlatformActions {
                override val repository = ScheduleRepository(directory).apply { importXls(sample(longContent = true)) }
                override fun openSchool() = Unit
                override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) = Unit
            }
            compose.setContent { App(actions, LocalDate.parse("2026-10-21")) }
            val content = "第一项\n第二项\n第三项\n第四项\n第五项\n第六项\n第七项\n第八项"
            compose.onNodeWithText("护理实验").performScrollTo().performClick()
            compose.onNodeWithText(content, useUnmergedTree = true).assertExists()
            compose.onNodeWithText("关闭详情").performClick()
            compose.onNodeWithText(content, useUnmergedTree = true).assertDoesNotExist()
        } finally { directory.toFile().deleteRecursively() }
    }

    private fun samplePeriods() = listOf("08:30-09:10", "09:20-10:00", "10:10-10:50", "11:00-11:40", "14:00-14:40", "14:50-15:30", "15:40-16:20", "16:30-17:10", "17:20-18:00", "18:00-18:40", "19:00-19:40", "19:50-20:30", "20:40-21:20", "21:30-22:10").mapIndexed { i, value -> cn.edu.ahtcm.schedule.core.ClassPeriod(i + 1, value.substringBefore('-'), value.substringAfter('-')) }

    private fun sample(longContent: Boolean = false, lesson: String = "护理实验", daytime: Boolean = false): ByteArray = HSSFWorkbook().use { book ->
        val sheet = book.createSheet("Sheet1")
        fun cell(r: Int, c: Int, v: String) { (sheet.getRow(r) ?: sheet.createRow(r)).createCell(c).setCellValue(v) }
        cell(0, 0, "示例2026-2027-1课表")
        cell(1, 0, "周次\n节次"); cell(1, 1, "第8周")
        cell(2, 0, ""); cell(2, 1, "10-19"); cell(2, 3, "10-21")
        cell(3, 0, "星期")
        if (daytime) {
            cell(4, 0, "第一大节")
            cell(4, 1, "基础护理学[理论]\n示例教师\n8-0102\n教学楼101")
        }
        cell(10, 0, "第七大节")
        val content = if (longContent) "第一项\n第二项\n第三项\n第四项\n第五项\n第六项\n第七项\n第八项" else "基础护理实训"
        cell(10, 3, "\n$lesson[实验实训]\n示例教师\n8-1314\n实训室4\n$content")
        java.io.ByteArrayOutputStream().also { book.write(it) }.toByteArray()
    }
}
