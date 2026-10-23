package com.smartisan.weather

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.ActivityNotFoundException
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.location.LocationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.smartisan.weather.appwidget.WeatherWidgetProvider
import com.smartisan.weather.bean.SmartisanLocation
import com.smartisan.weather.data.city.CityRepository
import com.smartisan.weather.data.location.LocationAccess
import com.smartisan.weather.data.model.Weather
import com.smartisan.weather.data.network.NetworkMonitor
import com.smartisan.weather.data.settings.WeatherSettings
import com.smartisan.weather.ui.alert.WeatherAlertActivity
import com.smartisan.weather.ui.citylist.CityListActivity
import com.smartisan.weather.ui.main.WeatherUiState
import com.smartisan.weather.ui.main.WeatherEvent
import com.smartisan.weather.ui.main.WeatherViewModel
import com.smartisan.weather.ui.navigation.WeatherEdgeToEdgeActivity
import com.smartisan.weather.ui.navigation.startWeatherActivityForResult
import com.smartisan.weather.ui.search.SearchCityActivity
import com.smartisan.weather.util.Constants
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartisan.weather.ui.main.WeatherScreen
import com.smartisan.weather.ui.navigation.toSearchLocation
import com.smartisan.weather.ui.startup.WeatherLocationDialog
import com.smartisan.weather.ui.startup.LocationNotice

import android.content.pm.PackageManager
import android.os.Build

/** Owns permissions, navigation and lifecycle; Compose owns the weather presentation. */
class MainActivity : WeatherEdgeToEdgeActivity() {
    private val viewModel by viewModels<WeatherViewModel>()
    private var weatherStarted by mutableStateOf(false)
    private var locationNotice by mutableStateOf<LocationNotice?>(null)
    private var awaitingLocationPermission = false
    private var awaitingLocationSettings = false
    private var firstStart = true
    private var initialSearchLaunched = false
    private var initialLocationRequested = false
    private var notificationPermissionAsked = false
    private var pendingWidgetCityKey: String? = null
    private val settings by lazy(LazyThreadSafetyMode.NONE) { WeatherSettings.getInstance(this) }
    private val networkMonitor by lazy(LazyThreadSafetyMode.NONE) { NetworkMonitor(this) }

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) {
            // 必须先等通知权限结果再启动天气：页面启动后会立刻申请定位权限，
            // 两个权限请求并行时系统只会保留一个，后发起的定位请求会被丢弃。
            startWeather()
        }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        continueLocationAfterExternalUi {
            awaitingLocationPermission = false
            if (LocationAccess.read(this) != LocationAccess.NONE) requestCurrentLocation()
            else if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                viewModel.locationUnavailable(R.string.weather_location_permission_denied)
            } else locationNotice = LocationNotice.PERMISSION
        }
    }

    private val locationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        continueLocationAfterExternalUi {
            awaitingLocationSettings = false
            when {
                LocationAccess.read(this) == LocationAccess.NONE ->
                    viewModel.locationUnavailable(R.string.weather_location_permission_denied)
                !isLocationEnabled() ->
                    viewModel.locationUnavailable(R.string.location_server_unavailable)
                else -> requestCurrentLocation()
            }
        }
    }

    private val searchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data
        if (data?.getBooleanExtra(SearchCityActivity.EXTRA_REQUEST_LOCATION, false) == true) {
            initialSearchLaunched = false
            startLocationFlow()
        } else {
            data?.getStringExtra(SearchCityActivity.EXTRA_SELECTED_CITY_KEY)?.let(viewModel::focusCity)
        }
    }

    private val cityListLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (
            result.resultCode == Activity.RESULT_OK &&
            result.data?.getBooleanExtra(SearchCityActivity.EXTRA_REQUEST_LOCATION, false) == true
        ) {
            startLocationFlow()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialSearchLaunched = savedInstanceState?.getBoolean("initialSearchLaunched") ?: false
        initialLocationRequested = savedInstanceState?.getBoolean("initialLocationRequested") ?: false
        locationNotice = savedInstanceState?.getString("locationNotice")?.let(LocationNotice::valueOf)
        awaitingLocationPermission = savedInstanceState?.getBoolean("awaitingLocationPermission") ?: false
        awaitingLocationSettings = savedInstanceState?.getBoolean("awaitingLocationSettings") ?: false
        pendingWidgetCityKey = intent.getStringExtra(WeatherWidgetProvider.EXTRA_CITY_KEY)
        setContent {
            if (weatherStarted) {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                WeatherScreen(
                    state = state,
                    onSelectCity = viewModel::setCurrentIndex,
                    onRefresh = ::refreshCurrentCity,
                    onToggleUnit = viewModel::toggleTempUnit,
                    onAddCity = ::addCity,
                    onManageCities = ::openCityList,
                    onOpenAlerts = { _, weather -> openAlerts(weather) },
                    onLocate = ::startLocationFlow,
                    onOpenSource = ::openSource,
                )
                LaunchedEffect(state.citiesLoaded, state.cities.isEmpty()) {
                    if (state.citiesLoaded && state.cities.isEmpty() && !initialLocationRequested) {
                        initialLocationRequested = true
                        startLocationFlow()
                    }
                }
            } else {
                WeatherScreen(
                    state = WeatherUiState(),
                    onSelectCity = {}, onRefresh = {}, onToggleUnit = {}, onAddCity = {},
                    onManageCities = {}, onOpenAlerts = { _, _ -> }, onLocate = {}, onOpenSource = {},
                )
            }
            locationNotice?.let { notice ->
                WeatherLocationDialog(
                    notice = notice,
                    onCancel = {
                        locationNotice = null
                        if (notice == LocationNotice.PRECISION) requestCurrentLocation()
                        else viewModel.locationUnavailable(
                            if (notice == LocationNotice.PERMISSION || notice == LocationNotice.RATIONALE) R.string.weather_location_permission_denied
                            else R.string.location_server_unavailable,
                        )
                    },
                    onSettings = {
                        locationNotice = null
                        if (notice == LocationNotice.RATIONALE) {
                            requestLocationPermissions()
                            return@WeatherLocationDialog
                        }
                        awaitingLocationSettings = true
                        val destination = if (notice == LocationNotice.SERVICES) {
                            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        } else {
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
                        }
                        try {
                            locationSettingsLauncher.launch(destination)
                        } catch (_: ActivityNotFoundException) {
                            awaitingLocationSettings = false
                            viewModel.locationUnavailable(R.string.weather_location_settings_unavailable)
                        }
                    },
                )
            }
        }
        requestNotificationPermissionThenStart()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("initialSearchLaunched", initialSearchLaunched)
        outState.putBoolean("initialLocationRequested", initialLocationRequested)
        outState.putString("locationNotice", locationNotice?.name)
        outState.putBoolean("awaitingLocationPermission", awaitingLocationPermission)
        outState.putBoolean("awaitingLocationSettings", awaitingLocationSettings)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        if (weatherStarted && !firstStart) viewModel.refreshAllCities()
        firstStart = false
    }

    override fun onStop() {
        if (weatherStarted && !isChangingConfigurations) viewModel.cancelLocation()
        super.onStop()
    }

    private fun startWeather() {
        if (weatherStarted || isFinishing || isDestroyed) return
        pendingWidgetCityKey?.let(viewModel::focusCity)
        pendingWidgetCityKey = null
        weatherStarted = true
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.uiState.first { it.citiesLoaded }
                if (!locationUiPending() && LocationAccess.read(this@MainActivity) != LocationAccess.NONE &&
                    isLocationEnabled()
                ) viewModel.refreshLocation(automatic = true)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.events.collect(::handleWeatherEvent) }
                launch {
                    var previousOnline: Boolean? = null
                    networkMonitor.isOnline.collect { online ->
                        when {
                            previousOnline == true && !online -> viewModel.markNetworkUnavailable()
                            previousOnline == false && online -> viewModel.refreshAllCities(forceRefresh = true)
                        }
                        previousOnline = online
                    }
                }
            }
        }
    }

    private fun handleWeatherEvent(event: WeatherEvent) {
        when (event) {
            is WeatherEvent.LocationFailed -> {
                Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
                if (viewModel.uiState.value.cities.isEmpty()) launchRequiredSearch()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(WeatherWidgetProvider.EXTRA_CITY_KEY)?.takeIf(String::isNotBlank)?.let {
            if (weatherStarted) viewModel.focusCity(it) else pendingWidgetCityKey = it
            return
        }
        if (weatherStarted && intent.getStringExtra(Constants.WEATHER_LAUNCH_PARAM) ==
            Constants.WEATHER_LUNCH_SOURCE_LAUNCHER_CARD
        ) viewModel.setCurrentIndex(0)
    }

    private fun openAlerts(weather: Weather) {
        if (weather.alert.isEmpty) return
        startActivity(Intent(this, WeatherAlertActivity::class.java).putExtra(WeatherAlertActivity.EXTRA_ALERT, weather.alert))
    }

    private fun openCityList() {
        startWeatherActivityForResult(cityListLauncher, Intent(this, CityListActivity::class.java))
    }

    private fun openSource(url: String) {
        val destination = url.takeIf { it.toUri().scheme.equals("https", ignoreCase = true) } ?: Constants.PARNTER_URL
        val intent = Intent(Intent.ACTION_VIEW, destination.toUri())
        // ACTION_VIEW can launch a browser even when package visibility hides it from queries.
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.weather_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshCurrentCity() {
        val city = viewModel.uiState.value.currentCity ?: return
        when {
            !city.isLocationCity -> viewModel.refreshCurrentCity()
            LocationAccess.read(this) == LocationAccess.NONE -> startLocationFlow()
            else -> requestCurrentLocation()
        }
    }

    private fun addCity() {
        if (viewModel.uiState.value.cities.size >= CityRepository.MAX_CITIES) {
            Toast.makeText(this, R.string.city_count_over_limit, Toast.LENGTH_SHORT).show()
            return
        }
        startSearchActivity(requireCity = false, currentLocation = viewModel.uiState.value.currentCity?.toSearchLocation())
    }

    private fun startSearchActivity(requireCity: Boolean, currentLocation: SmartisanLocation? = null) {
        val state = viewModel.uiState.value
        val intent = Intent(this, SearchCityActivity::class.java).apply {
            putExtra(SearchCityActivity.EXTRA_REQUIRE_CITY, requireCity)
            putExtra(Constants.WEATHER_SEARCH_CITY_PARAMETER_LOCATION, currentLocation)
            putStringArrayListExtra(Constants.WEATHER_SEARCH_CITY_PARAMETER_CITYIDS, ArrayList(state.cities.map { it.locationKey }))
            state.cities.firstOrNull { it.isLocationCity }?.let {
                putExtra(Constants.WEATHER_SEARCH_CITY_LOCATION_CITY, it.toSearchLocation())
            }
        }
        startWeatherActivityForResult(searchLauncher, intent)
    }

    private fun launchRequiredSearch() {
        if (initialSearchLaunched || isFinishing || isDestroyed) return
        initialSearchLaunched = true
        startSearchActivity(requireCity = true)
    }

    private fun startLocationFlow() {
        if (viewModel.uiState.value.isLocating || locationUiPending()) return
        when (LocationAccess.read(this)) {
            LocationAccess.NONE -> {
                if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    locationNotice = LocationNotice.RATIONALE
                } else requestLocationPermissions()
            }
            LocationAccess.APPROXIMATE -> locationNotice = LocationNotice.PRECISION
            LocationAccess.PRECISE -> requestCurrentLocation()
        }
    }

    /**
     * 启动天气前先处理通知权限。
     *
     * Android 同一时刻只允许一个权限请求，因此这里等待通知权限结果后再调用 [startWeather]，
     * 由页面接着走定位权限流程，保证两个请求不会互相丢弃。
     *
     * 只在从未询问过时弹窗；用户拒绝后不再重复打扰，权限在系统设置里恢复后后台任务会自动开始推送。
     */
    private fun requestNotificationPermissionThenStart() {
        if (
            notificationPermissionAsked ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startWeather()
            return
        }
        notificationPermissionAsked = true
        lifecycleScope.launch {
            if (settings.readNotificationPermissionRequested()) {
                startWeather()
                return@launch
            }
            // 记录已询问过，之后启动不再重复弹窗，用户可自行到系统设置开启。
            settings.markNotificationPermissionRequested()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestLocationPermissions() {
        awaitingLocationPermission = true
        locationPermissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    private fun requestCurrentLocation() {
        when {
            LocationAccess.read(this) == LocationAccess.NONE -> locationNotice = LocationNotice.PERMISSION
            !isLocationEnabled() -> locationNotice = LocationNotice.SERVICES
            else -> viewModel.refreshLocation()
        }
    }

    private fun isLocationEnabled(): Boolean = getSystemService(LocationManager::class.java)?.let {
        LocationManagerCompat.isLocationEnabled(it)
    } == true

    private fun locationUiPending(): Boolean =
        locationNotice != null || awaitingLocationPermission || awaitingLocationSettings

    private fun continueLocationAfterExternalUi(action: () -> Unit) {
        // Activity results can arrive before onCreate finishes wiring the state collectors.
        startWeather()
        action()
    }
}
