package cn.edu.ahtcm.schedule.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.testTag
import cn.edu.ahtcm.schedule.core.*
import java.nio.file.Files
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

class AcademicCalendarUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `calendar entry displays cached official weeks and date notes offline on a phone`() {
        val dir = Files.createTempDirectory("academic-calendar-ui")
        try {
            val store = AcademicCalendarStore(dir)
            val term = AcademicTerm("202601", "2026-2027-1")
            store.saveTerms(AcademicCalendarTerms(listOf(term), term.code))
            store.save(calendar(term, "学校日期备注"))
            val actions = object : PlatformActions {
                override val repository = ScheduleRepository(dir)
                override val academicCalendarStore = store
                override fun openSchool() = Unit
                override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) = Unit
            }
            compose.setContent { Box(Modifier.requiredSize(390.dp, 760.dp).testTag("calendar-phone")) { App(actions, LocalDate.parse("2027-01-01")) } }
            compose.onNodeWithText("校历").performClick()
            compose.onNodeWithText("2027年1月").assertExists()
            compose.onNodeWithText("教学周").assertExists()
            compose.onNodeWithText("离线查看 · 连接教务后可检查更新").assertExists()
            val preview = org.jetbrains.skia.Image.makeFromBitmap(compose.onNodeWithTag("calendar-phone").captureToImage().asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!
            val previewPath = java.nio.file.Path.of("build", "preview-academic-calendar-phone.png")
            Files.createDirectories(previewPath.parent)
            Files.write(previewPath, preview.bytes)
            compose.onNodeWithTag("academic-day-2027-01-01").performClick()
            compose.onNodeWithText("学校日期备注").assertExists()
            compose.onNodeWithText("第 1 教学周").assertExists()
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithText("我的课表").performClick()
            compose.onNodeWithText("2027年1月").assertDoesNotExist()
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `switching cached terms never shows previous term dates under new label`() {
        val dir = Files.createTempDirectory("academic-calendar-term-ui")
        try {
            val store = AcademicCalendarStore(dir)
            val first = AcademicTerm("202601", "2026-2027-1")
            val other = AcademicTerm("202602", "2026-2027-2")
            store.saveTerms(AcademicCalendarTerms(listOf(first, other), first.code))
            store.save(calendar(first, "第一学期备注"))
            val actions = object : PlatformActions {
                override val repository = ScheduleRepository(dir)
                override val academicCalendarStore = store
                override fun openSchool() = Unit
                override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) = Unit
            }
            compose.setContent { App(actions, LocalDate.parse("2027-01-01")) }
            compose.onNodeWithText("校历").performClick()
            compose.onNodeWithText("连接教务").performClick()
            compose.onNodeWithText("选择学期").performClick()
            compose.onNodeWithText(other.label).performClick()
            compose.onNodeWithText("校历").performClick()
            compose.onNodeWithText("这个学期还没有缓存校历").assertExists()
            compose.onNodeWithTag("academic-day-2027-01-01").assertDoesNotExist()
            compose.onNodeWithText("同步校历").assertDoesNotExist()
            compose.onNodeWithText("选择学期").assertDoesNotExist()
            compose.onNodeWithText("官方教学校历").assertDoesNotExist()
            compose.onNodeWithText("连接教务").performClick()
            compose.onNodeWithText("选择学期").performClick()
            compose.onNodeWithText(first.label).performClick()
            compose.onNodeWithText("校历").performClick()
            compose.onNodeWithTag("academic-day-2027-01-01").assertExists()
            compose.onNodeWithText("前往连接教务").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("从教务系统获取课表").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("从教务系统获取课表").assertExists()
        } finally { dir.toFile().deleteRecursively() }
    }

    private fun calendar(term: AcademicTerm, note: String): AcademicCalendarSnapshot {
        val first = LocalDate.parse("2026-12-28")
        return AcademicCalendarSnapshot(term, (0..13).map {
            AcademicCalendarDay(first.plusDays(it.toLong()).toString(), it / 7 + 1, if (it == 4) note else "")
        }, "2026-10-24T00:00:00Z")
    }
}
