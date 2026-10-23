package com.smartisan.weather.appwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.location.LocationManagerCompat
import com.smartisan.weather.data.city.CityRepository
import com.smartisan.weather.data.location.DeviceLocationSource
import com.smartisan.weather.data.location.LocationAccess
import com.smartisan.weather.data.location.LocationCityResolver
import com.smartisan.weather.data.weather.WeatherRepository
import com.smartisan.weather.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 定位城市的小组件「刷新」按钮的前台定位通道。
 *
 * 后台任务拿不到位置：后台定位需要 `ACCESS_BACKGROUND_LOCATION`（必须单独跳系统设置授予），
 * 且 Doze 维护窗口内 GPS/网络 provider 基本不可用。所以这个动作改由不可见 Activity 执行，
 * 复用用户已经授予的前台定位权限走完一整遍流程：
 *
 * 取一次坐标 → 反查城市 → 写入定位城市 → 刷新该城天气 → 重绘组件 → [finish]。
 *
 * 任何一步失败（无权限、系统定位关闭、无 fix、反查失败、超出 [LOCATION_WAIT_MILLIS]）都回退成
 * 「按已保存的城市 Key 刷新」，保证用户点下去一定有反馈，也不会把组件永久卡在刷新态。
 */
class WeatherWidgetRefreshActivity : ComponentActivity() {

    /**
     * 刻意不跟随 Activity 生命周期取消：用户点完刷新立刻回桌面时（`noHistory` 会让本页立即销毁），
     * 定位仍应跑完并把组件状态收尾，否则组件会停在「刷新中」。任务结束即释放自己。
     */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent.getIntExtra(EXTRA_APP_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        workScope.launch {
            try {
                refreshWidget(appWidgetId)
            } finally {
                // Activity 已经销毁时 finish() 是空操作，调用是安全的。
                finish()
                workScope.cancel()
            }
        }
    }

    private suspend fun refreshWidget(appWidgetId: Int) {
        // 组件刷新要走完网络请求，后续步骤与 Activity 存活与否无关，统一用 Application 上下文。
        val context = applicationContext
        // 先落一次「刷新中」，避免定位期间组件毫无变化。
        WeatherWidgetUpdater.renderCached(
            context = context,
            requestedIds = intArrayOf(appWidgetId),
            updateState = WeatherWidgetUpdateState.REFRESHING,
        )
        val cityKey = resolveLocationCityKey(context)
        if (cityKey == null) {
            // 定位不可用：退回普通刷新，由 Worker 复用已保存的城市 Key 完成请求与重绘。
            WeatherWidgetScheduler.requestRefresh(
                context = context,
                appWidgetId = appWidgetId,
                manual = true,
            )
            return
        }

        val weather = try {
            WeatherRepository(context).fetchWeather(cityKey = cityKey, notifyWidgets = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DebugLog.log(TAG, "Widget weather request failed: ${error.javaClass.simpleName}")
            null
        }
        val succeeded = weather?.isComplete == true
        WeatherWidgetUpdater.renderCached(
            context = context,
            requestedIds = intArrayOf(appWidgetId),
            updateState = if (succeeded) {
                WeatherWidgetUpdateState.IDLE
            } else {
                WeatherWidgetUpdateState.FAILED
            },
            noCacheState = if (succeeded) null else WeatherWidgetEmptyState.WEATHER_UNAVAILABLE,
        )
    }

    /** @return 定位成功后的城市 Key；定位或城市反查失败时返回 `null`。 */
    private suspend fun resolveLocationCityKey(context: Context): String? = try {
        val location = withTimeoutOrNull(LOCATION_WAIT_MILLIS) {
            DeviceLocationSource(context).getCurrentLocation()
        }
        val city = location?.let { LocationCityResolver(context).resolve(it).getOrNull() }
        if (city == null) {
            null
        } else {
            // 本页自己会请求并重绘该城市，因此不再让城市写入顺带入队一次全量刷新。
            val added = CityRepository(context).setLocationCity(city, refreshWidgets = false)
            if (added == CityRepository.AddResult.SUCCESS) city.cityId else null
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // 只记录异常类型，坐标与城市名不写日志。
        DebugLog.log(TAG, "Widget location refresh failed: ${error.javaClass.simpleName}")
        null
    }

    companion object {
        private const val TAG = "WidgetRefresh"
        private const val EXTRA_APP_WIDGET_ID = "com.smartisan.weather.appwidget.extra.APP_WIDGET_ID"

        /** 比 [DeviceLocationSource] 内部的 20 秒更短：用户点刷新按钮时不该等这么久。 */
        private const val LOCATION_WAIT_MILLIS = 8_000L

        /**
         * 组件是否具备「点击后重新定位」的条件。
         * 缺少定位权限或系统定位开关关闭时保持普通刷新，避免多起一个空转的透明页面。
         */
        fun canLocate(context: Context): Boolean {
            if (LocationAccess.read(context) == LocationAccess.NONE) return false
            val manager = context.getSystemService(LocationManager::class.java) ?: return false
            return LocationManagerCompat.isLocationEnabled(manager)
        }

        fun intent(context: Context, appWidgetId: Int): Intent =
            Intent(context, WeatherWidgetRefreshActivity::class.java).apply {
                // 每个组件用独立 data，保证 PendingIntent 不会被系统合并。
                data = Uri.Builder()
                    .scheme("smartisanweather")
                    .authority("weather-widget")
                    .appendPath(appWidgetId.toString())
                    .appendPath("locate-refresh")
                    .build()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(EXTRA_APP_WIDGET_ID, appWidgetId)
            }
    }
}
