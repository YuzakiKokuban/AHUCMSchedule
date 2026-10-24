package cn.edu.ahtcm.schedule.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.edu.ahtcm.schedule.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.*
import org.junit.Rule
import org.junit.Test

class AdaptiveWindowUiTest {
    @get:Rule val compose = createComposeRule()
    private data class Viewport(val width: Int, val height: Int, val desktop: Boolean, val fontScale: Float = 1f)

    @Test fun `desktop landscape and tablet reflow preserve selected course and week`() {
        val dir = Files.createTempDirectory("adaptive-window")
        try {
            val viewport = mutableStateOf(Viewport(1180, 820, true))
            val repo = ScheduleRepository(dir)
            assertIs<UpdateResult.Success>(repo.importXls(layoutXls()))
            val actions = object : PlatformActions {
                override val desktop get() = viewport.value.desktop
                override val repository = repo
                override val academicCalendarStore = AcademicCalendarStore(dir).apply { saveTerms(AcademicCalendarTerms(listOf(layoutTerm), layoutTerm.code)); save(layoutCalendar()) }
                override fun openSchool() = Unit
                override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) = Unit
            }
            compose.setContent {
                val view = viewport.value
                val density = minOf(1f, 1000f / view.width, 740f / view.height)
                CompositionLocalProvider(LocalDensity provides Density(density, view.fontScale)) {
                    Box(Modifier.requiredSize(view.width.dp, view.height.dp).testTag("adaptive-window")) { App(actions, LocalDate.parse("2026-10-21")) }
                }
            }
            compose.onNodeWithTag("desktop-sidebar").assertExists()
            compose.onNodeWithTag("course-detail-pane").assertExists()
            compose.onNodeWithText("基础护理学").performClick()
            compose.onNodeWithText("示例教学内容，可在详情中完整查看").assertIsDisplayed()
            capture("windows-workspace")
            compose.runOnIdle { viewport.value = Viewport(844, 390, false) }
            compose.onNodeWithTag("navigation-rail").assertExists()
            compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
            compose.onNodeWithTag("course-detail-pane").assertDoesNotExist()
            compose.onNodeWithText("关闭详情").performScrollTo().performClick()
            assertTrue(compose.onNodeWithText("基础护理学").fetchSemanticsNode().boundsInRoot.top < 170f, "Landscape toolbar must leave room for the timetable")
            capture("android-landscape")
            compose.onNodeWithContentDescription("下一周").performClick()
            compose.runOnIdle { viewport.value = Viewport(1280, 800, false) }
            compose.onNodeWithText("10/26 — 11/1").assertExists()
            compose.onNodeWithContentDescription("上一周").performClick()
            capture("tablet-landscape")
            compose.onNodeWithTag("destination-3").performClick()
            compose.onAllNodesWithText("2026年10月").onFirst().assertExists()
            compose.onNodeWithContentDescription("下一月").performClick()
            compose.onAllNodesWithText("2026年11月").onFirst().assertExists()
            compose.runOnIdle { viewport.value = Viewport(390, 844, false) }
            compose.onNodeWithTag("bottom-navigation").assertExists()
            compose.onAllNodesWithText("2026年11月").onFirst().assertExists()
            capture("phone-calendar")
            compose.onNodeWithTag("destination-0").performClick()
            compose.onNodeWithTag("destination-3").performClick()
            compose.onAllNodesWithText("2026年11月").onFirst().assertExists()
            compose.onNodeWithText("同步校历").assertDoesNotExist()
            compose.onNodeWithText("选择学期").assertDoesNotExist()
            compose.runOnIdle { viewport.value = Viewport(800, 1280, false, 1.3f) }
            compose.onAllNodesWithText("2026年11月").onFirst().assertExists()
            compose.onNodeWithText("2026年12月").assertIsDisplayed()
            capture("tablet-portrait")
            compose.runOnIdle { viewport.value = Viewport(760, 650, true) }
            compose.onNodeWithTag("navigation-rail").assertExists()
        } finally { dir.toFile().deleteRecursively() }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bytes = org.jetbrains.skia.Image.makeFromBitmap(compose.onNodeWithTag("adaptive-window").captureToImage().asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
        val target = Path.of("build", "preview-$name.png")
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
    }
}
