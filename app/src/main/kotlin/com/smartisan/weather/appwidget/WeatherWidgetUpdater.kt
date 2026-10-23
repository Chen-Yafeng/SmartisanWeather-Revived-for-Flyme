package com.smartisan.weather.appwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import com.smartisan.weather.data.city.CityRepository
import com.smartisan.weather.data.model.SavedCity
import com.smartisan.weather.data.settings.WeatherSettings
import com.smartisan.weather.data.weather.WeatherRepository
import com.smartisan.weather.data.weather.shouldRefreshWeatherCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

internal object WeatherWidgetUpdater {
    /** Null states preserve the current status while only the cached content is invalidated. */
    suspend fun renderCached(
        context: Context,
        requestedIds: IntArray? = null,
        updateState: WeatherWidgetUpdateState? = null,
        noCacheState: WeatherWidgetEmptyState? = null,
    ) {
        val appContext = context.applicationContext
        val appWidgetManager = AppWidgetManager.getInstance(appContext)
        val ids = activeIds(appContext, appWidgetManager, requestedIds)
        val glanceManager = GlanceAppWidgetManager(appContext)
        ids.forEach { appWidgetId ->
            val glanceId = glanceManager.getGlanceIdBy(appWidgetId)
            updateAppWidgetState(appContext, glanceId) {
                it[WeatherWidgetGlanceState.REVISION] =
                    (it[WeatherWidgetGlanceState.REVISION] ?: 0L) + 1L
                updateState?.let { state ->
                    it[WeatherWidgetGlanceState.UPDATE_STATE] = state.name
                }
                noCacheState?.let { state ->
                    it[WeatherWidgetGlanceState.NO_CACHE_STATE] = state.name
                }
            }
            WeatherGlanceWidget.update(appContext, glanceId)
        }
    }

    suspend fun refresh(
        context: Context,
        requestedIds: IntArray? = null,
        manual: Boolean,
    ) = refreshMutex.withLock {
        val appContext = context.applicationContext
        val appWidgetManager = AppWidgetManager.getInstance(appContext)
        val ids = activeIds(appContext, appWidgetManager, requestedIds)
        if (ids.isEmpty()) return@withLock

        val resolved = resolveTargets(appContext, ids)
        if (resolved !is ResolvedTargets.Ready) {
            renderCached(appContext, ids)
            return@withLock
        }
        if (manual) {
            renderCached(
                context = appContext,
                requestedIds = ids,
                updateState = WeatherWidgetUpdateState.REFRESHING,
            )
        }

        val weatherRepository = WeatherRepository(appContext)

        // 只刷新组件真正显示的城市。周期性全量刷新由 WeatherRefreshWorker 统一负责，
        // 这里若再顺带刷全部中国城市，会让一次点击膨胀成 N 个请求，并让组件迟迟回不到 IDLE。
        //
        // 非手动触发（桌面 onUpdate、配置变更、App 更新、组件重排）还要过一遍缓存年龄：
        // 桌面会在系统切换深色模式等场景反复唤醒 onUpdate，不判定就会一路重复请求。
        val widgetRefreshSemaphore = Semaphore(MAX_CONCURRENT_WIDGET_REFRESHES)
        val citiesToRefresh = resolved.targets
            .map(SavedWidgetTarget::city)
            .distinctBy(SavedCity::locationKey)
            .filter { city ->
                manual || shouldRefreshWeatherCache(
                    updatedAtMillis =
                        weatherRepository.getCachedWeatherUpdatedAt(city.locationKey) ?: 0L,
                )
            }

        // 全部命中新鲜缓存时仍然重绘：主题切换等场景需要的正是「按当前配置重新解析资源」。
        if (citiesToRefresh.isEmpty()) {
            renderCached(context = appContext, requestedIds = ids)
            return@withLock
        }

        val failedKeys = coroutineScope {
            citiesToRefresh
                .map { city ->
                    async {
                        val weather = widgetRefreshSemaphore.withPermit {
                            try {
                                weatherRepository.fetchWeather(
                                    cityKey = city.locationKey,
                                    notifyWidgets = false,
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        }

                        city.locationKey.takeUnless {
                            weather?.isComplete == true
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
                .toSet()
        }

        val attemptedKeys = resolved.targets.mapTo(mutableSetOf()) { it.city.locationKey }
        val finalIds = activeIds(appContext, appWidgetManager, requestedIds = null)
        val finalTargets = resolveTargets(appContext, finalIds)
        if (finalTargets !is ResolvedTargets.Ready) {
            renderCached(
                context = appContext,
                requestedIds = finalIds,
                updateState = WeatherWidgetUpdateState.IDLE,
            )
            return@withLock
        }
        val requestedIdSet = ids.toSet()
        finalTargets.targets
            .filter { it.city.locationKey in attemptedKeys }
            .forEach { target ->
                renderCached(
                    context = appContext,
                    requestedIds = intArrayOf(target.appWidgetId),
                    updateState = if (
                        manual &&
                        target.appWidgetId in requestedIdSet &&
                        target.city.locationKey in failedKeys
                    ) {
                        WeatherWidgetUpdateState.FAILED
                    } else {
                        WeatherWidgetUpdateState.IDLE
                    },
                    noCacheState = WeatherWidgetEmptyState.WEATHER_UNAVAILABLE,
                )
            }
    }

    private suspend fun resolveTargets(context: Context, ids: IntArray): ResolvedTargets {
        val settings = WeatherSettings.getInstance(context)
        val cities = CityRepository(context).savedCities.first()
        if (cities.isEmpty()) return ResolvedTargets.Empty
        val selections = settings.readWidgetCitySelections(ids)
        val targets = ids.asSequence().mapNotNull { appWidgetId ->
            WeatherWidgetCityResolver.resolve(cities, selections[appWidgetId])?.let { city ->
                SavedWidgetTarget(appWidgetId = appWidgetId, city = city)
            }
        }.toList()
        return if (targets.isEmpty()) ResolvedTargets.Empty else ResolvedTargets.Ready(targets)
    }

    private fun activeIds(
        context: Context,
        appWidgetManager: AppWidgetManager,
        requestedIds: IntArray?,
    ): IntArray {
        val active = appWidgetManager.getAppWidgetIds(
            ComponentName(context, WeatherWidgetProvider::class.java),
        ).toSet()
        return (requestedIds?.asSequence() ?: active.asSequence())
            .filter(active::contains)
            .distinct()
            .toList()
            .toIntArray()
    }

    private data class SavedWidgetTarget(
        val appWidgetId: Int,
        val city: SavedCity,
    )

    private sealed interface ResolvedTargets {
        data object Empty : ResolvedTargets
        data class Ready(val targets: List<SavedWidgetTarget>) : ResolvedTargets
    }

    private val refreshMutex = Mutex()
    private const val MAX_CONCURRENT_WIDGET_REFRESHES = 2
}
