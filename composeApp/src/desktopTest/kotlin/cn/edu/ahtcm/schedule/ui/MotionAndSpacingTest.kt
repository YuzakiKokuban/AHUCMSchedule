package cn.edu.ahtcm.schedule.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import cn.edu.ahtcm.schedule.core.*
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class MotionAndSpacingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `calendar next and previous month slide in opposite directions`() {
        val dir = Files.createTempDirectory("calendar-motion")
        try {
            val platform = actions(dir)
            compose.setContent { Box(Modifier.requiredSize(390.dp, 720.dp)) { App(platform, LocalDate.parse("2026-10-21")) } }
            compose.onNodeWithTag("destination-3").performClick()
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription("下一月").performClick()
            compose.mainClock.advanceTimeBy(64)
            val nextMoving = compose.onNodeWithTag("academic-day-2026-11-02").fetchSemanticsNode().boundsInRoot.left
            compose.mainClock.advanceTimeBy(500)
            val nextSettled = compose.onNodeWithTag("academic-day-2026-11-02").fetchSemanticsNode().boundsInRoot.left
            assertTrue(nextMoving > nextSettled + 1f, "Next month must enter from the right: $nextMoving -> $nextSettled")
            compose.onNodeWithContentDescription("上一月").performClick()
            compose.mainClock.advanceTimeBy(64)
            val previousMoving = compose.onNodeWithTag("academic-day-2026-10-01").fetchSemanticsNode().boundsInRoot.left
            compose.mainClock.advanceTimeBy(500)
            val previousSettled = compose.onNodeWithTag("academic-day-2026-10-01").fetchSemanticsNode().boundsInRoot.left
            assertTrue(previousMoving < previousSettled - 1f, "Previous month must enter from the left")
        } finally { compose.mainClock.autoAdvance = true; dir.toFile().deleteRecursively() }
    }

    @Test fun `calendar to notices follows visible navigation order rather than internal page ids`() {
        val dir = Files.createTempDirectory("navigation-direction")
        try {
            compose.setContent { Box(Modifier.requiredSize(390.dp, 720.dp)) { App(actions(dir), LocalDate.parse("2026-10-21")) } }
            compose.onNodeWithTag("destination-3").performClick()
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("destination-1").performClick()
            compose.mainClock.advanceTimeBy(64)
            val forwardMoving = compose.onNodeWithText("通知仅供查阅，课程安排以重新获取的课表为准。").fetchSemanticsNode().boundsInRoot.left
            compose.mainClock.advanceTimeBy(500)
            val forwardSettled = compose.onNodeWithText("通知仅供查阅，课程安排以重新获取的课表为准。").fetchSemanticsNode().boundsInRoot.left
            assertTrue(forwardMoving > forwardSettled + 1f, "Notices are after calendar and must enter from the right")
            compose.onNodeWithTag("destination-3").performClick()
            compose.mainClock.advanceTimeBy(64)
            val backMoving = compose.onNodeWithTag("academic-day-2026-10-01").fetchSemanticsNode().boundsInRoot.left
            compose.mainClock.advanceTimeBy(500)
            val backSettled = compose.onNodeWithTag("academic-day-2026-10-01").fetchSemanticsNode().boundsInRoot.left
            assertTrue(backMoving < backSettled - 1f, "Returning to calendar must enter from the left")
        } finally { compose.mainClock.autoAdvance = true; dir.toFile().deleteRecursively() }
    }

    @Test fun `semester options have separate touch surfaces with a visible gap`() {
        val dir = Files.createTempDirectory("semester-spacing")
        try {
            val platform = actions(dir)
            compose.setContent { Box(Modifier.requiredSize(390.dp, 720.dp)) { App(platform) } }
            compose.onNodeWithTag("destination-2").performClick()
            compose.onNodeWithText("选择学期").performClick()
            val first = compose.onNodeWithText("2026-2027-2").fetchSemanticsNode().boundsInRoot
            val next = compose.onAllNodesWithText("2026-2027-1").fetchSemanticsNodes().map { it.boundsInRoot }.first { it.top >= first.bottom }
            assertTrue(next.top - first.bottom >= 8f, "Semester options need visible spacing between their touch surfaces")
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `rapid reversal and resize settle on one correct month without losing navigation state`() {
        val dir = Files.createTempDirectory("motion-interruption")
        try {
            val width = mutableStateOf(390)
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(0.7f)) {
                    Box(Modifier.requiredSize(width.value.dp, 720.dp)) { App(actions(dir), LocalDate.parse("2026-10-21")) }
                }
            }
            compose.onNodeWithTag("destination-3").performClick()
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription("下一月").performClick()
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithContentDescription("上一月").performClick()
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithContentDescription("下一月").performClick()
            compose.runOnIdle { width.value = 1280 }
            compose.mainClock.advanceTimeBy(600)
            compose.onAllNodesWithTag("academic-day-2026-11-02").assertCountEquals(1)
            compose.onNodeWithTag("academic-day-2026-10-01").assertDoesNotExist()
            compose.onNodeWithText("本月").performClick()
            compose.mainClock.advanceTimeBy(600)
            compose.onAllNodesWithTag("academic-day-2026-10-01").assertCountEquals(1)
            compose.onNodeWithTag("destination-2").performClick()
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithTag("destination-1").performClick()
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithTag("destination-3").performClick()
            compose.mainClock.advanceTimeBy(600)
            compose.onAllNodesWithTag("academic-day-2026-10-01").assertCountEquals(1)
        } finally { compose.mainClock.autoAdvance = true; dir.toFile().deleteRecursively() }
    }

    private fun actions(dir: java.nio.file.Path) = object : PlatformActions {
        override val repository = ScheduleRepository(dir)
        override val academicCalendarStore = AcademicCalendarStore(dir).apply {
            saveTerms(AcademicCalendarTerms(listOf(layoutTerm, AcademicTerm("202602", "2026-2027-2")), layoutTerm.code))
            save(layoutCalendar())
        }
        override fun openSchool() = Unit
        override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) = Unit
    }
}
