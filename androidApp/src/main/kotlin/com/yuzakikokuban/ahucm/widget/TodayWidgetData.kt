package com.yuzakikokuban.ahucm.widget

import android.content.Context
import android.os.UserManager
import cn.edu.ahtcm.schedule.core.*
import java.nio.file.Files
import java.time.Instant

internal data class WidgetSnapshot(val owner: String?, val day: DaySchedule, val message: String? = null)

internal object TodayWidgetData {
    fun owner(context: Context): String? {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return null
        val marker = context.filesDir.toPath().resolve("schedule/active-account")
        return runCatching {
            if (!Files.exists(marker)) "manual"
            else if (Files.size(marker) != 64L) null
            else Files.newInputStream(marker).bufferedReader().use { it.readText() }.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        }.getOrNull()
    }

    fun read(context: Context, now: Instant = Instant.now()): WidgetSnapshot {
        val empty = TodaySchedule.project(emptyList(), null, now)
        val owner = owner(context) ?: return WidgetSnapshot(null, empty, "请解锁并打开应用读取课表")
        return runCatching {
            // A fresh store observes atomic commits made while the app was running.
            val storage = AccountStorage(context.filesDir.toPath().resolve("schedule"))
            if ((storage.activeAccountKey ?: "manual") != owner) return WidgetSnapshot(null, empty)
            val day = TodaySchedule.project(storage.schedule.snapshots(), storage.academicCalendar.availableTerms()?.currentCode, now)
            WidgetSnapshot(owner, day, storage.schedule.loadError?.let { "课表缓存读取失败，请打开应用同步" })
        }.getOrElse { WidgetSnapshot(owner, empty, "课表缓存读取失败，请打开应用同步") }
    }
}
