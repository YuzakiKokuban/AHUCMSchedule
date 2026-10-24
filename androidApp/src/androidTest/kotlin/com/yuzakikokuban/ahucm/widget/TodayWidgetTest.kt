package com.yuzakikokuban.ahucm.widget

import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.ahtcm.schedule.core.*
import com.yuzakikokuban.ahucm.R
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodayWidgetTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val now = Instant.parse("2026-10-24T00:10:00Z")
    private fun fixture() = ScheduleSnapshot("202601", "2026–2027 第1学期", "2026-08-31", "2026-10-23T12:00:00Z",
        listOf(Lesson("中医基础理论", "理论", "示例教师", "教学楼 101", "", "2026-10-24", 8, listOf(1, 2))), "2027-01-17")

    @Test fun currentAccountCacheNeverFallsBackToPreviousAccount() {
        val temp = Files.createTempDirectory(context.cacheDir.toPath(), "widget-test-").toFile()
        val isolated = object : ContextWrapper(context) { override fun getFilesDir(): File = temp }
        try {
            val storage = AccountStorage(temp.toPath().resolve("schedule"))
            storage.select("anonymous-widget-a")
            val cache = File(temp, "schedule/accounts/${storage.activeAccountKey}/timetable.json")
            cache.parentFile!!.mkdirs()
            cache.writeText("""{"schema":1,"activeTerm":"202601","terms":{"202601":${Json.encodeToString(fixture())}}}""")
            val previous = TodayWidgetData.read(isolated, now)
            assertEquals("中医基础理论", previous.day.courses.single().name)
            storage.select("anonymous-widget-b")
            assertNotEquals(previous.owner, TodayWidgetData.owner(isolated))
            assertEquals(DayAvailability.NoCache, TodayWidgetData.read(isolated, now).day.availability)
        } finally { temp.walkBottomUp().forEach { it.delete() } }
    }

    @Test fun nativeCollectionAndEmptyStatesInflateInBothThemes() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val course = TodayWidgetData.read(context, now).copy(owner = "anonymous", day = TodaySchedule.project(listOf(fixture()), "202601", now), message = null)
        instrumentation.runOnMainSync {
            listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES).forEach { mode ->
                val config = Configuration(context.resources.configuration).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode }
                val themed = context.createConfigurationContext(config)
                val view = TodayWidgetViews.create(themed, course, 77).apply(themed, FrameLayout(themed))
                assertTrue(view.findViewById<TextView>(R.id.widget_date).text.contains("10月24日"))
                assertEquals(1, view.findViewById<ListView>(R.id.widget_courses).adapter.count)
                val row = TodayWidgetViews.row(themed, course, 0).apply(themed, FrameLayout(themed))
                assertEquals("中医基础理论", row.findViewById<TextView>(R.id.widget_course_name).text.toString())
                assertEquals("作息待同步", row.findViewById<TextView>(R.id.widget_badge).text.toString())
                val many = course.copy(day = course.day.copy(courses = List(40) { course.day.courses.single() }))
                TodayWidgetViews.row(themed, many, 32).reapply(themed, row)
                assertEquals("", row.findViewById<TextView>(R.id.widget_badge).text.toString())
                assertEquals("", row.findViewById<TextView>(R.id.widget_location).text.toString())
                assertTrue(row.findViewById<TextView>(R.id.widget_course_name).text.contains("另有 8 项"))
                val density = themed.resources.displayMetrics.density
                val width = (320 * density).toInt(); val height = (300 * density).toInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                File(context.cacheDir, "widget-${if (mode == Configuration.UI_MODE_NIGHT_YES) "night" else "day"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                val empty = WidgetSnapshot(null, TodaySchedule.project(emptyList(), null, now))
                val emptyView = TodayWidgetViews.create(themed, empty, 78).apply(themed, FrameLayout(themed))
                assertEquals(0, emptyView.findViewById<ListView>(R.id.widget_courses).adapter.count)
                assertTrue(emptyView.findViewById<TextView>(R.id.widget_empty).text.contains("同步或导入"))
            }
        }
    }
}
