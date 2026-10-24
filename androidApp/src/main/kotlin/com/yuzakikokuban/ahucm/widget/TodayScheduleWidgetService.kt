package com.yuzakikokuban.ahucm.widget

import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** Collection host for Android 8–11. Android 12+ receives direct collection items. */
class TodayScheduleWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(intent.data?.getQueryParameter("owner"))

    private inner class Factory(private val expectedOwner: String?) : RemoteViewsFactory {
        private var data: WidgetSnapshot? = null
        override fun onCreate() = Unit
        override fun onDataSetChanged() { data = TodayWidgetData.read(this@TodayScheduleWidgetService) }
        override fun onDestroy() { data = null }
        override fun getCount() = current()?.let(TodayWidgetViews::count) ?: 0
        override fun getViewAt(position: Int): RemoteViews? = current()?.takeIf { position in 0 until TodayWidgetViews.count(it) }?.let { TodayWidgetViews.row(this@TodayScheduleWidgetService, it, position) }
        private fun current() = data?.takeIf { it.owner != null && it.owner == expectedOwner && it.owner == TodayWidgetData.owner(this@TodayScheduleWidgetService) }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
    }
}
