package com.smartisan.weather.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.smartisan.weather.appwidget.WeatherWidgetEmptyState
import com.smartisan.weather.appwidget.WeatherWidgetScheduler
import com.smartisan.weather.appwidget.WeatherWidgetUpdater
import com.smartisan.weather.data.city.CityRepository
import com.smartisan.weather.data.settings.WeatherSettings
import com.smartisan.weather.data.weather.WeatherRepository
import com.smartisan.weather.data.weather.shouldRefreshWeatherCache
import com.smartisan.weather.notification.WeatherAlertNotifier
import com.smartisan.weather.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 统一的周期刷新任务：一轮网络请求同时服务天气预警通知与桌面小组件。
 *
 * 缓存写入和预警通知去重都由 [WeatherRepository.fetchWeather] 内部完成，
 * 本 Worker 只在刷新结束后重绘小组件，不会发起第二轮请求。
 * 失败保持静默，等待下一个巡检周期。
 */
internal class WeatherRefreshWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {

    override suspend fun doWork(): Result {
        val settings = WeatherSettings.getInstance(applicationContext)

        // 通知关闭或没有通知权限时，不必为预警消耗流量；此时若存在小组件仍需刷新数据。
        val alertActive = settings.readAlertNotificationsEnabled() &&
            WeatherAlertNotifier.canPostNotifications(applicationContext)
        val widgetActive = WeatherWidgetScheduler.hasWidgets(applicationContext)
        if (!alertActive && !widgetActive) return Result.success()

        val cities = CityRepository(applicationContext).savedCities.first()
        if (cities.isEmpty()) {
            if (widgetActive) WeatherWidgetUpdater.renderCached(applicationContext)
            return Result.success()
        }

        val weatherRepository = WeatherRepository(applicationContext)
        val nowMillis = System.currentTimeMillis()
        // 与前台共用同一个 10 分钟判定：刚被主界面刷新过的城市直接跳过，不重复请求。
        val staleCities = cities.filter { city ->
            shouldRefreshWeatherCache(
                updatedAtMillis =
                    weatherRepository.getCachedWeatherUpdatedAt(city.locationKey) ?: 0L,
                nowMillis = nowMillis,
            )
        }
        // 保留前台天气、搜索和定位请求的并发预算，避免后台任务占满全局连接池。
        val refreshLimiter = Semaphore(MAX_CONCURRENT_REFRESHES)

        val failedKeys = coroutineScope {
            staleCities.map { city ->
                async {
                    val weather = refreshLimiter.withPermit {
                        try {
                            weatherRepository.fetchWeather(
                                cityKey = city.locationKey,
                                notifyWidgets = false,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            DebugLog.log(TAG, "Refresh failed: ${error.javaClass.simpleName}")
                            null
                        }
                    }
                    city.locationKey.takeUnless { weather?.isComplete == true }
                }
            }.awaitAll().filterNotNull().toSet()
        }

        if (widgetActive) {
            // 已保存城市的范围覆盖了“小组件绑定城市 + 中国城市”的旧集合，
            // 因此这里只需按最新缓存重绘，不再单独发请求。
            WeatherWidgetUpdater.renderCached(
                context = applicationContext,
                noCacheState = if (failedKeys.isEmpty()) {
                    null
                } else {
                    WeatherWidgetEmptyState.WEATHER_UNAVAILABLE
                },
            )
        }
        // 整轮全部失败才重试：部分成功说明网络和接口基本可用，等下一个周期即可，
        // 全部失败则很可能是瞬时故障或断网刚恢复，让 WorkManager 按退避再跑一次。
        return if (staleCities.isNotEmpty() && failedKeys.size == staleCities.size) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    private companion object {
        const val TAG = "WeatherRefresh"
        const val MAX_CONCURRENT_REFRESHES = 2
    }
}
