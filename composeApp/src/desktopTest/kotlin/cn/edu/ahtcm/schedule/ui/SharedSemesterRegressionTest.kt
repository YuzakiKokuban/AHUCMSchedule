package cn.edu.ahtcm.schedule.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import cn.edu.ahtcm.schedule.core.*
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test

class SharedSemesterRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `import preference write failure keeps imported term coherent and shows feedback`() {
        val dir = Files.createTempDirectory("import-semester-failure")
        try {
            val selection = TermSelectionStore(dir)
            Files.createDirectory(dir.resolve("selected-term.json"))
            Files.writeString(dir.resolve("selected-term.json/block"), "block replacement")
            val platform = object : PlatformActions {
                override val repository = ScheduleRepository(dir)
                override val termSelection = selection
                override fun openSchool() = Unit
                override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) = onResult(layoutXls(), null)
            }
            compose.setContent { App(platform, LocalDate.parse("2026-10-21")) }
            compose.onNodeWithTag("destination-2").performClick()
            compose.onNodeWithText("导入官方 XLS 文件").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("课表已导入，但学期选择无法保存，下次打开可能需要重新选择").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("destination-0").performClick()
            compose.onNodeWithText("基础护理学").assertExists()
            assertNull(selection.selected())
            assertEquals("202601", platform.repository.snapshot()!!.termCode)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `account change while semester persistence waits cannot publish or persist old preference`() = runBlocking {
        val dir = Files.createTempDirectory("semester-owner-race")
        val ownerA = ScheduleRepository(dir.resolve("a"))
        val ownerB = ScheduleRepository(dir.resolve("b"))
        val owner = AtomicReference(ownerA)
        val storeA = TermSelectionStore(dir.resolve("a"))
        val storeB = TermSelectionStore(dir.resolve("b"))
        val lockHeld = CountDownLatch(1)
        val release = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val reads = AtomicInteger()
        val holder = thread {
            synchronized(storeA) { lockHeld.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        try {
            assertTrue(lockHeld.await(5, TimeUnit.SECONDS))
            val platform = object : PlatformActions {
                override val repository get() = owner.get()
                override val termSelection get(): TermSelectionStore {
                    if (reads.incrementAndGet() >= 2) captured.countDown()
                    return if (owner.get() === ownerA) storeA else storeB
                }
                override fun openSchool() = Unit
                override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) = Unit
            }
            val pending = async(Dispatchers.Default) { persistTermSelection(platform, layoutTerm, ownerA) }
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            owner.set(ownerB)
            release.countDown()
            assertFailsWith<CancellationException> { pending.await() }
            assertNull(storeA.selected())
            assertNull(storeB.selected())
            assertFalse(Files.exists(dir.resolve("a/selected-term.json")))
        } finally { release.countDown(); holder.join(5000); dir.toFile().deleteRecursively() }
    }

    @Test fun `Android management controls do not silently export current semester while viewing history`() {
        val dir = Files.createTempDirectory("history-management")
        try {
            val platform = object : PlatformActions {
                override val repository = ScheduleRepository(dir).apply { importXls(layoutXls()) }
                override val currentTermOnlyIntegrations = true
                override val academicCalendarStore = AcademicCalendarStore(dir).apply {
                    saveTerms(AcademicCalendarTerms(listOf(layoutTerm, AcademicTerm("202602", "2026-2027-2")), "202602"))
                }
                override val calendarActionLabel = "同步到系统日历"
                override fun openSchool() = Unit
                override fun chooseXls(onResult: (ByteArray?, String?) -> Unit) = Unit
            }
            compose.setContent { App(platform, LocalDate.parse("2026-10-21")) }
            compose.onNodeWithTag("destination-2").performClick()
            compose.onNodeWithText("同步到系统日历").assertDoesNotExist()
            compose.onNodeWithText("系统日历和小爱自动管理仅用于学校当前学期").performScrollTo().assertIsDisplayed()
        } finally { dir.toFile().deleteRecursively() }
    }
}
