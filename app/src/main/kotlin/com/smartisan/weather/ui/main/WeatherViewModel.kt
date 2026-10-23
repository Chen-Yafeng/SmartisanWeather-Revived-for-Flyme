package com.smartisan.weather.ui.main

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartisan.weather.R
import com.smartisan.weather.data.city.CityRepository
import com.smartisan.weather.data.location.LocationCityFailureReason
import com.smartisan.weather.data.location.LocationCityResolutionException
import com.smartisan.weather.data.location.LocationCityResolver
import com.smartisan.weather.data.location.DeviceLocationSource
import com.smartisan.weather.data.model.SavedCity
import com.smartisan.weather.data.model.Weather
import com.smartisan.weather.data.settings.WeatherSettings
import com.smartisan.weather.data.weather.AUTOMATIC_WEATHER_REFRESH_INTERVAL_MILLIS
import com.smartisan.weather.data.weather.WeatherRepository
import com.smartisan.weather.data.weather.shouldRefreshWeatherCache
import com.smartisan.weather.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 主界面状态：城市列表 + 每个城市的天气数据。
 */
data class WeatherUiState(
    val citiesLoaded: Boolean = false,
    val cities: List<SavedCity> = emptyList(),
    val weathers: Map<String, Weather> = emptyMap(),
    val currentIndex: Int = 0,
    val isCelsius: Boolean = true,
    val isLocating: Boolean = false,
    val loadingKeys: Set<String> = emptySet(),
    val errorsByKey: Map<String, String> = emptyMap(),
    val loadVersions: Map<String, Long> = emptyMap(),
) {
    val currentCity: SavedCity?
        get() = cities.getOrNull(currentIndex)

    val currentWeather: Weather?
        get() = currentCity?.let { weathers[it.locationKey] }

    val isLoading: Boolean
        get() = currentCity?.locationKey in loadingKeys

    val error: String?
        get() = currentCity?.let { errorsByKey[it.locationKey] }
}

sealed interface WeatherEvent {
    data class LocationFailed(@param:StringRes val message: Int) : WeatherEvent
}

/**
 * 主界面 ViewModel。
 */
class WeatherViewModel(app: Application) : AndroidViewModel(app) {

    private val cityRepo = CityRepository(app)
    private val weatherRepo = WeatherRepository(app)
    private val settings = WeatherSettings.getInstance(app)
    private val locationResolver = LocationCityResolver(app)
    private val locationSource = DeviceLocationSource(app)

    /** 定位开始时已强制刷新的城市，用于避免定位结束/失败后的重复请求。 */
    private var prefetchedKey: String? = null
    private var prefetchedAtMillis: Long = 0L

    private val _uiState = MutableStateFlow(WeatherUiState())
    val uiState: StateFlow<WeatherUiState> = _uiState.asStateFlow()
    private val eventChannel = Channel<WeatherEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private val loadJobs = mutableMapOf<String, Job>()
    private val pendingForceRefreshKeys = mutableSetOf<String>()
    private var locationJob: Job? = null
    private var pendingFocusKey: String? = null

    init {
        // 观察城市列表
        viewModelScope.launch {
            cityRepo.savedCities.collectLatest { cities ->
                val cityKeys = cities.mapTo(mutableSetOf()) { it.locationKey }
                loadJobs.keys.filterNot(cityKeys::contains).forEach { key ->
                    loadJobs.remove(key)?.cancel()
                }
                pendingForceRefreshKeys.retainAll(cityKeys)
                _uiState.update { state ->
                    val currentKey = state.currentCity?.locationKey
                    val preservedIndex = cities.indexOfFirst { it.locationKey == currentKey }
                    val requestedIndex = cities.indexOfFirst {
                        it.locationKey == pendingFocusKey
                    }
                    if (requestedIndex >= 0) pendingFocusKey = null
                    val nextIndex = when {
                        cities.isEmpty() -> 0
                        requestedIndex >= 0 -> requestedIndex
                        preservedIndex >= 0 -> preservedIndex
                        else -> state.currentIndex.coerceIn(cities.indices)
                    }
                    state.copy(
                        citiesLoaded = true,
                        cities = cities,
                        currentIndex = nextIndex,
                        weathers = state.weathers.filterKeys(cityKeys::contains),
                        loadingKeys = state.loadingKeys.intersect(cityKeys),
                        errorsByKey = state.errorsByKey.filterKeys(cityKeys::contains),
                        loadVersions = state.loadVersions.filterKeys(cityKeys::contains),
                    )
                }
                // 为每个城市加载天气
                cities.forEach { city ->
                    if (!_uiState.value.weathers.containsKey(city.locationKey)) {
                        loadWeather(city.locationKey)
                    }
                }
            }
        }
        // 观察温度单位设置
        viewModelScope.launch {
            settings.isCelsius.collectLatest { isC ->
                _uiState.update { it.copy(isCelsius = isC) }
            }
        }
    }

    /** 加载天气数据；自动加载复用短时间内的新鲜缓存，用户刷新始终请求网络。 */
    fun loadWeather(cityKey: String, forceRefresh: Boolean = false) {
        if (cityKey.isBlank()) return
        if (loadJobs[cityKey]?.isActive == true) {
            if (forceRefresh) pendingForceRefreshKeys += cityKey
            return
        }
        loadJobs[cityKey] = viewModelScope.launch {
            val initialState = _uiState.value
            var notifyLoadCompletion = initialState.errorsByKey.containsKey(cityKey)
            _uiState.update { state ->
                state.copy(
                    loadingKeys = state.loadingKeys + cityKey,
                    errorsByKey = state.errorsByKey - cityKey,
                )
            }
            var failure: String? = null
            var networkRefreshSucceeded = false
            try {
                val snapshot = weatherRepo.getCachedWeatherSnapshot(cityKey)
                if (snapshot != null) {
                    _uiState.update { state ->
                        if (state.cities.none { it.locationKey == cityKey }) state
                        else state.copy(weathers = state.weathers + (cityKey to snapshot.weather))
                    }
                }

                val cacheIsFresh = snapshot?.let { cached ->
                    cached.weather.isComplete &&
                        !shouldRefreshWeatherCache(cached.updatedAtMillis)
                } == true
                val shouldFetch = forceRefresh || !cacheIsFresh
                notifyLoadCompletion = notifyLoadCompletion || shouldFetch
                if (shouldFetch) {
                    val weather = weatherRepo.fetchWeather(cityKey)
                    if (weather != null && weather.isComplete) {
                        networkRefreshSucceeded = true
                        _uiState.update { state ->
                            if (state.cities.none { it.locationKey == cityKey }) state
                            else state.copy(weathers = state.weathers + (cityKey to weather))
                        }
                    } else {
                        failure = "获取天气数据失败"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failure = "获取天气数据失败"
            } finally {
                _uiState.update { state ->
                    if (state.cities.none { it.locationKey == cityKey }) {
                        return@update state.copy(
                            loadingKeys = state.loadingKeys - cityKey,
                            errorsByKey = state.errorsByKey - cityKey,
                            loadVersions = state.loadVersions - cityKey,
                        )
                    }
                    state.copy(
                        loadingKeys = state.loadingKeys - cityKey,
                        errorsByKey = if (failure == null) {
                            state.errorsByKey - cityKey
                        } else {
                            state.errorsByKey + (cityKey to failure)
                        },
                        loadVersions = if (notifyLoadCompletion) {
                            val nextVersion = (state.loadVersions[cityKey] ?: 0L) + 1L
                            state.loadVersions + (cityKey to nextVersion)
                        } else {
                            state.loadVersions
                        },
                    )
                }
                loadJobs.remove(cityKey)
                if (
                    pendingForceRefreshKeys.remove(cityKey) &&
                    !networkRefreshSucceeded &&
                    _uiState.value.cities.any { it.locationKey == cityKey }
                ) {
                    loadWeather(cityKey, forceRefresh = true)
                }
            }
        }
    }

    /** 刷新当前城市天气 */
    fun refreshCurrentCity() {
        val city = _uiState.value.currentCity ?: return
        loadWeather(city.locationKey, forceRefresh = true)
    }

    fun refreshAllCities(forceRefresh: Boolean = false) {
        _uiState.value.cities.forEach { city ->
            loadWeather(city.locationKey, forceRefresh = forceRefresh)
        }
    }

    fun markNetworkUnavailable() {
        _uiState.update { state ->
            val cityKeys = state.cities.mapTo(mutableSetOf(), SavedCity::locationKey)
            state.copy(
                loadingKeys = emptySet(),
                errorsByKey = cityKeys.associateWith { NETWORK_ERROR },
                loadVersions = state.loadVersions + cityKeys.associateWith { key ->
                    (state.loadVersions[key] ?: 0L) + 1L
                },
            )
        }
    }

    fun focusCity(cityKey: String) {
        if (cityKey.isBlank()) return
        pendingFocusKey = cityKey
        _uiState.update { state ->
            val index = state.cities.indexOfFirst { it.locationKey == cityKey }
            if (index < 0 || index == state.currentIndex) state else state.copy(currentIndex = index)
        }
    }

    /**
     * 前台定位：取坐标 → 反查城市 → 写入定位城市 → 刷新天气。
     *
     * [automatic] 为 true 时受 10 分钟节流，时间戳存 DataStore（跨进程重启有效），
     * 同时要求已经存在定位城市；手动入口（刷新按钮、定位入口、搜索页定位）不受节流限制。
     */
    fun refreshLocation(automatic: Boolean = false) {
        if (locationJob?.isActive == true) return
        val previousCity = _uiState.value.cities.firstOrNull(SavedCity::isLocationCity)
        // 没有定位城市时自动定位没有意义：用户可能刚把它删掉，不应该自己长回来。
        if (automatic && previousCity == null) return
        locationJob = viewModelScope.launch {
            val nowMillis = System.currentTimeMillis()
            if (
                automatic &&
                !shouldRefreshLocation(settings.readLastLocationAttemptMillis(), nowMillis)
            ) {
                return@launch
            }
            settings.markLocationAttempt(nowMillis)
            _uiState.update { it.copy(isLocating = true) }
            // 取坐标最长要等 20 秒。先把已有定位城市的天气刷起来，点下去就有反馈；
            // 若定位结果落在同一座城市，随后那一步会因为缓存刚写入而跳过，不会重复请求。
            val prefetched = previousCity?.locationKey?.also { key ->
                prefetchedKey = key
                prefetchedAtMillis = nowMillis
                loadWeather(key, forceRefresh = true)
            }
            try {
                val location = locationSource.getCurrentLocation()
                if (location == null) {
                    if (!automatic) locationUnavailable(R.string.weather_location_fix_unavailable)
                    return@launch
                }
                val city = locationResolver.resolve(location).getOrThrow()
                // 前台会自己请求并重绘小组件，所以城市写入不再顺带入队一次组件刷新。
                if (
                    cityRepo.setLocationCity(city, refreshWidgets = false) ==
                    CityRepository.AddResult.LIMIT_EXCEEDED
                ) {
                    if (!automatic) locationUnavailable(R.string.city_count_over_limit)
                    return@launch
                }
                // Room emits asynchronously. Publish the target before starting its weather job,
                // otherwise a fast response could be discarded as belonging to an unknown city.
                _uiState.first { state -> state.cities.any { it.locationKey == city.cityId } }
                if (!automatic) focusCity(city.cityId)
                loadWeather(
                    cityKey = city.cityId,
                    forceRefresh = !automatic && city.cityId != prefetched,
                )
            } catch (cancelled: CancellationException) {
                // A stopped screen must not report failure or turn cancellation into a refresh.
                // 同时清掉节流时间戳，取消不占用接下来 10 分钟的自动定位机会。
                withContext(NonCancellable) { settings.clearLocationAttempt() }
                throw cancelled
            } catch (error: Exception) {
                DebugLog.log(
                    LOCATION_TAG,
                    "Location failed: " +
                        ((error as? LocationCityResolutionException)?.reason?.name
                            ?: error.javaClass.simpleName),
                )
                if (!automatic) {
                    val message = when ((error as? LocationCityResolutionException)?.reason) {
                        LocationCityFailureReason.CITY_SEARCH_FAILED -> R.string.findcity_update_failed_network_unavailable
                        LocationCityFailureReason.CITY_NOT_FOUND,
                        LocationCityFailureReason.INVALID_LOCATION -> R.string.update_location_not_find_city
                        null -> R.string.weather_location_fix_unavailable
                    }
                    locationUnavailable(message)
                }
            } finally {
                _uiState.update { it.copy(isLocating = false) }
                locationJob = null
            }
        }
    }

    fun cancelLocation() {
        locationJob?.cancel()
    }

    fun locationUnavailable(@StringRes message: Int) {
        // A failed coordinate fix must not prevent a user from refreshing existing weather.
        // 若定位开始时已经强制刷过同一座城市，这里就只走缓存判定，避免同一城市连发两次请求。
        _uiState.value.cities.firstOrNull(SavedCity::isLocationCity)?.let { city ->
            val alreadyRefreshed = city.locationKey == prefetchedKey &&
                System.currentTimeMillis() - prefetchedAtMillis <
                AUTOMATIC_WEATHER_REFRESH_INTERVAL_MILLIS
            loadWeather(city.locationKey, forceRefresh = !alreadyRefreshed)
        }
        eventChannel.trySend(WeatherEvent.LocationFailed(message))
    }

    /** 切换城市页面 */
    fun setCurrentIndex(index: Int) {
        _uiState.update { state ->
            if (index !in state.cities.indices || index == state.currentIndex) state
            else state.copy(currentIndex = index)
        }
    }

    /** 切换温度单位 */
    fun toggleTempUnit() {
        viewModelScope.launch {
            val newUnit = if (_uiState.value.isCelsius) WeatherSettings.UNIT_FAHRENHEIT else WeatherSettings.UNIT_CELSIUS
            settings.setTempUnit(newUnit)
        }
    }

    /** 删除城市 */
    fun deleteCity(key: String) {
        viewModelScope.launch {
            cityRepo.deleteCity(key)
            _uiState.update { it.copy(weathers = it.weathers - key) }
        }
    }

    /** 城市重排序 */
    fun swapCities(from: Int, to: Int) {
        val cities = _uiState.value.cities
        if (from !in cities.indices || to !in cities.indices) return
        viewModelScope.launch {
            cityRepo.swapSortOrder(cities[from], cities[to])
        }
    }

    private companion object {
        const val LOCATION_TAG = "WeatherLocation"
        const val NETWORK_ERROR = "网络连接不可用"
    }
}
