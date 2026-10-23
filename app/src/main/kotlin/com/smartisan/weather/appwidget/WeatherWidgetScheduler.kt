package com.smartisan.weather.appwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * 小组件的即时任务调度。
 *
 * 周期刷新已合并进 [com.smartisan.weather.background.WeatherRefreshScheduler]，
 * 这里只保留用户可见动作触发的、需要立即执行且带状态反馈的一次性任务。
 */
internal object WeatherWidgetScheduler {
    fun requestRefresh(
        context: Context,
        appWidgetId: Int? = null,
        manual: Boolean = false,
    ) {
        if (!hasWidgets(context)) return
        val request = OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
            .setInputData(workerData(appWidgetId, manual, MODE_REFRESH))
            .addTag(WORK_TAG)
            .build()
        val suffix = appWidgetId?.toString() ?: "all"
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "$IMMEDIATE_WORK_PREFIX$suffix",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun requestRender(context: Context) {
        if (!hasWidgets(context)) return
        val request = OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_MODE, MODE_RENDER)
                    .build(),
            )
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            RENDER_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun requestRestore(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val request = OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_MODE, MODE_RESTORE)
                    .putIntArray(KEY_OLD_APP_WIDGET_IDS, oldWidgetIds)
                    .putIntArray(KEY_APP_WIDGET_IDS, newWidgetIds)
                    .build(),
            )
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            RESTORE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun workerData(appWidgetId: Int?, manual: Boolean, mode: String): Data =
        Data.Builder()
            .putString(KEY_MODE, mode)
            .putInt(KEY_APP_WIDGET_ID, appWidgetId ?: AppWidgetManager.INVALID_APPWIDGET_ID)
            .putBoolean(KEY_MANUAL, manual)
            .build()

    /** 供后台周期任务判断是否需要刷新与重绘；没有小组件时不必为组件做任何事。 */
    fun hasWidgets(context: Context): Boolean =
        AppWidgetManager.getInstance(context).getAppWidgetIds(
            ComponentName(context, WeatherWidgetProvider::class.java),
        ).isNotEmpty()

    const val KEY_APP_WIDGET_ID = "app_widget_id"
    const val KEY_APP_WIDGET_IDS = "app_widget_ids"
    const val KEY_OLD_APP_WIDGET_IDS = "old_app_widget_ids"
    const val KEY_MANUAL = "manual"
    const val KEY_MODE = "mode"
    const val MODE_REFRESH = "refresh"
    const val MODE_RENDER = "render"
    const val MODE_RESTORE = "restore"
    private const val IMMEDIATE_WORK_PREFIX = "weather-widget-refresh-"
    private const val RENDER_WORK_NAME = "weather-widget-render"
    private const val RESTORE_WORK_NAME = "weather-widget-restore"
    private const val WORK_TAG = "weather-widget"
}
