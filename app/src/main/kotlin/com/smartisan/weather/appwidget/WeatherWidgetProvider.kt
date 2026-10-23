package com.smartisan.weather.appwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.smartisan.weather.background.WeatherRefreshScheduler

class WeatherWidgetProvider : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherGlanceWidget

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WeatherRefreshScheduler.ensureScheduled(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WeatherRefreshScheduler.ensureScheduled(context)
        WeatherWidgetScheduler.requestRefresh(context)
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        super.onRestored(context, oldWidgetIds, newWidgetIds)
        WeatherWidgetScheduler.requestRestore(context, oldWidgetIds, newWidgetIds)
        WeatherRefreshScheduler.ensureScheduled(context)
    }

    // 移除小组件后不取消周期任务：预警巡检与组件无关，仍需继续运行。

    companion object {
        const val EXTRA_CITY_KEY = "com.smartisan.weather.appwidget.extra.CITY_KEY"
    }
}
