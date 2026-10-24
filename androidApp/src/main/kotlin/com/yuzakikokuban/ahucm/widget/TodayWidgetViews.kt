package com.yuzakikokuban.ahucm.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import cn.edu.ahtcm.schedule.core.*
import com.yuzakikokuban.ahucm.MainActivity
import com.yuzakikokuban.ahucm.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object TodayWidgetViews {
    private const val MAX_ROWS = 32
    fun count(data: WidgetSnapshot) = if (data.message != null) 0 else minOf(data.day.courses.size, MAX_ROWS) + if (data.day.courses.size > MAX_ROWS) 1 else 0

    fun create(context: Context, data: WidgetSnapshot, id: Int): RemoteViews = RemoteViews(context.packageName, R.layout.widget_today_schedule).apply {
        val day = data.day
        val weekday = listOf("一", "二", "三", "四", "五", "六", "日")[day.date.dayOfWeek.value - 1]
        setTextViewText(R.id.widget_date, "${day.date.monthValue}月${day.date.dayOfMonth}日 周$weekday")
        val status = when {
            day.courses.any { it.moment == CourseMoment.Ongoing } -> "正在上课 · 共 ${day.courses.size} 项"
            day.courses.any { it.moment == CourseMoment.Next } -> "下一节待上 · 共 ${day.courses.size} 项"
            day.courses.isNotEmpty() && day.courses.all { it.moment == CourseMoment.Finished } -> "今天的课程已结束"
            else -> day.termLabel.orEmpty().take(40)
        }
        setTextViewText(R.id.widget_summary, status)
        setViewVisibility(R.id.widget_summary, if (status.isBlank()) android.view.View.GONE else android.view.View.VISIBLE)
        setTextViewText(R.id.widget_empty, data.message ?: when (day.availability) {
            DayAvailability.NoCache -> "请先打开应用同步或导入课表"
            DayAvailability.NoCourses -> "今天没有课程"
            DayAvailability.OutsideCachedTerms -> "今天不属于已同步的学期，请打开应用同步"
            DayAvailability.InvalidCache -> "课表读取失败，请打开应用同步"
            DayAvailability.Ready -> "今天没有课程"
        })
        val timestamp = day.retrievedAt?.let { value -> runCatching {
            Instant.parse(value).atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        }.getOrNull() }
        setTextViewText(R.id.widget_source, if (timestamp != null) "更新于 $timestamp · 点击打开" else "点击打开岐黄课表")
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        setOnClickPendingIntent(R.id.widget_header, open)
        setOnClickPendingIntent(R.id.widget_summary, open)
        setOnClickPendingIntent(R.id.widget_empty, open)
        setOnClickPendingIntent(R.id.widget_source, open)
        setPendingIntentTemplate(R.id.widget_courses, open)
        if (Build.VERSION.SDK_INT >= 31) {
            val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1).setHasStableIds(false)
            repeat(count(data)) { index -> items.addItem(index.toLong(), row(context, data, index)) }
            setRemoteAdapter(R.id.widget_courses, items.build())
        } else {
            @Suppress("DEPRECATION")
            setRemoteAdapter(R.id.widget_courses, Intent(context, TodayScheduleWidgetService::class.java).setData(Uri.parse("qihuang-widget://today/$id").buildUpon().appendQueryParameter("owner", data.owner ?: "unavailable").build()))
        }
        setEmptyView(R.id.widget_courses, R.id.widget_empty)
        if (count(data) == 0) {
            setViewVisibility(R.id.widget_courses, android.view.View.GONE)
            setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE)
        }
    }

    fun row(context: Context, data: WidgetSnapshot, index: Int): RemoteViews = RemoteViews(context.packageName, R.layout.widget_today_course).apply {
        val course = data.day.courses.getOrNull(index).takeIf { index < MAX_ROWS }
        setOnClickFillInIntent(R.id.widget_course, Intent())
        if (course == null) {
            setTextViewText(R.id.widget_course_name, "另有 ${maxOf(0, data.day.courses.size - MAX_ROWS)} 项，请打开应用查看")
            setTextViewText(R.id.widget_time, "更多")
            setTextViewText(R.id.widget_sections, "")
            setTextViewText(R.id.widget_location, "")
            setTextViewText(R.id.widget_badge, "")
            setInt(R.id.widget_course, "setBackgroundResource", R.drawable.widget_course_background)
        } else {
            setTextViewText(R.id.widget_course_name, course.name.take(160))
            setTextViewText(R.id.widget_location, course.location.ifBlank { "教室待定" }.take(120))
            setTextViewText(R.id.widget_time, if (course.start != null && course.end != null) "${course.start}\n${course.end}" else "时间待同步")
            setTextViewText(R.id.widget_sections, "第${course.sections.first()}${if (course.sections.size > 1) "–${course.sections.last()}" else ""}节")
            setTextViewText(R.id.widget_badge, when (course.moment) {
                CourseMoment.Ongoing -> "进行中"
                CourseMoment.Next -> "下一节"
                CourseMoment.Finished -> "已结束"
                CourseMoment.Unknown -> "作息待同步"
                CourseMoment.Later -> "待上课"
            })
            setInt(R.id.widget_course, "setBackgroundResource", if (course.moment in listOf(CourseMoment.Ongoing, CourseMoment.Next)) R.drawable.widget_course_selected else R.drawable.widget_course_background)
        }
    }
}
