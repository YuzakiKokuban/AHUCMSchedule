package cn.edu.ahtcm.schedule.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import cn.edu.ahtcm.schedule.core.*
import java.nio.file.Files
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

class PeriodSwipeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `swipes change timetable weeks while taps still open lessons`() {
        val dir = Files.createTempDirectory("week-swipe")
        try {
            val actions = object : PlatformActions {
                override val repository = ScheduleRepository(dir).apply { importXls(layoutXls()) }
                override fun openSchool() = Unit
                override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) = Unit
            }
            compose.setContent { Box(Modifier.requiredSize(390.dp, 760.dp)) { App(actions, LocalDate.parse("2026-10-21")) } }
            compose.onNodeWithText("第 8 周").assertExists()
            compose.onNodeWithTag("week-swipe").performTouchInput { swipeLeft() }
            compose.onNodeWithText("10/26 — 11/1").assertExists()
            compose.onNodeWithTag("week-swipe").performTouchInput { swipeRight() }
            compose.onNodeWithText("第 8 周").assertExists()
            compose.onNodeWithText("基础护理学").performClick()
            compose.onNodeWithText("示例教学内容，可在详情中完整查看").assertExists()
            compose.onNodeWithText("关闭详情").performScrollTo().performClick()
            compose.onNodeWithTag("week-swipe").performTouchInput { swipeUp() }
            compose.onNodeWithText("第 8 周").assertExists()
            compose.onNodeWithText("基础护理学").assertIsNotDisplayed()
            compose.onNodeWithTag("week-swipe").performTouchInput { swipeDown() }
            compose.onNodeWithTag("week-swipe").performTouchInput { swipe(center, center.copy(x = center.x - 20f)) }
            compose.onNodeWithText("第 8 周").assertExists()
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `calendar swipes change months and stop at cached boundaries`() {
        val dir = Files.createTempDirectory("month-swipe")
        try {
            val actions = object : PlatformActions {
                override val repository = ScheduleRepository(dir).apply { importXls(layoutXls()) }
                override val academicCalendarStore = AcademicCalendarStore(dir).apply { save(layoutCalendar()) }
                override fun openSchool() = Unit
                override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) = Unit
            }
            compose.setContent { Box(Modifier.requiredSize(390.dp, 760.dp)) { App(actions, LocalDate.parse("2026-10-21")) } }
            compose.onNodeWithTag("destination-3").performClick()
            compose.onNodeWithText("2026年10月").assertExists()
            compose.onNodeWithTag("calendar-swipe").performTouchInput { swipeLeft() }
            compose.onNodeWithText("2026年11月").assertExists()
            compose.onNodeWithTag("calendar-swipe").performTouchInput { swipeRight() }
            compose.onNodeWithText("2026年10月").assertExists()
            repeat(3) { compose.onNodeWithTag("calendar-swipe").performTouchInput { swipeRight() } }
            compose.onNodeWithText("2026年8月").assertExists()
            compose.onNodeWithTag("calendar-swipe").performTouchInput { swipeLeft() }
            compose.onNodeWithText("2026年9月").assertExists()
        } finally { dir.toFile().deleteRecursively() }
    }
}
