package com.yuzakikokuban.ahucm.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.yuzakikokuban.ahucm.R
import java.util.concurrent.Executors

class TodayScheduleWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                AppWidgetManager.ACTION_APPWIDGET_UPDATE, AppWidgetManager.ACTION_APPWIDGET_ENABLED,
                AppWidgetManager.ACTION_APPWIDGET_DISABLED, AppWidgetManager.ACTION_APPWIDGET_DELETED,
                AppWidgetManager.ACTION_APPWIDGET_RESTORED, AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED,
                Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_USER_UNLOCKED,
                "${context.packageName}.widget.REFRESH"
            )) return
        // All work is offline. goAsync keeps the receiver alive during cache IO.
        val pending = goAsync()
        TodayWidgetUpdates.enqueue(context) { pending.finish() }
    }
}

object TodayWidgetUpdates {
    private val executor = Executors.newSingleThreadExecutor()
    private val publication = Any()
    private fun ids(context: Context) = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, TodayScheduleWidget::class.java))

    fun request(context: Context) = enqueue(context.applicationContext)

    /** Account publication and widget publication share a lock in the app process. */
    fun accountChanged(context: Context, select: () -> Unit) {
        synchronized(publication) {
            val before = TodayWidgetData.owner(context)
            select()
            if (before != TodayWidgetData.owner(context)) {
                val empty = WidgetSnapshot(null, cn.edu.ahtcm.schedule.core.TodaySchedule.project(emptyList(), null, java.time.Instant.now()), "正在读取当前账号课表")
                val manager = AppWidgetManager.getInstance(context)
                val widgetIds = ids(context)
                widgetIds.forEach { manager.updateAppWidget(it, TodayWidgetViews.create(context, empty, it)) }
                if (Build.VERSION.SDK_INT < 31) {
                    @Suppress("DEPRECATION")
                    manager.notifyAppWidgetViewDataChanged(widgetIds, R.id.widget_courses)
                }
            }
        }
        request(context)
    }

    internal fun enqueue(context: Context, finished: () -> Unit = {}) {
        val app = context.applicationContext
        executor.execute {
            try { refresh(app) }
            catch (_: Exception) { android.util.Log.w("TodayWidget", "Widget refresh unavailable") }
            finally { finished() }
        }
    }

    private fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val widgetIds = ids(context)
        val alarm = context.getSystemService(AlarmManager::class.java)
        val tick = PendingIntent.getBroadcast(context, 730, Intent(context, TodayScheduleWidget::class.java).setAction("${context.packageName}.widget.REFRESH"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (widgetIds.isEmpty()) { alarm.cancel(tick); return }
        val data = TodayWidgetData.read(context)
        synchronized(publication) {
            if (data.owner != TodayWidgetData.owner(context)) return
            widgetIds.forEach { manager.updateAppWidget(it, TodayWidgetViews.create(context, data, it)) }
            if (Build.VERSION.SDK_INT < 31) {
                @Suppress("DEPRECATION")
                manager.notifyAppWidgetViewDataChanged(widgetIds, R.id.widget_courses)
            }
            // Non-wakeup and inexact: no exact-alarm permission or battery wakeups.
            alarm.set(AlarmManager.RTC, maxOf(System.currentTimeMillis() + 1000, data.day.nextChange.toEpochMilli()), tick)
        }
    }
}
