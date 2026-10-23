package com.smartisan.weather.data.settings

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.smartisan.weather.appwidget.WeatherWidgetUpdateNotifier
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.weatherDataStore by preferencesDataStore(name = "weather_settings")

/**
 * 应用级设置仓库。
 *
 * DataStore 是唯一持久化来源；[tempUnit] 同时提供同步可读的内存快照，供原版 View
 * 在绘制和动画期间读取，避免再维护一份 SharedPreferences 状态。
 */
class WeatherSettings private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = appContext.weatherDataStore.data.catch { error ->
        if (error is IOException) {
            emit(emptyPreferences())
        } else {
            throw error
        }
    }

    val tempUnit: StateFlow<Int> = preferences
        .map { it[KEY_TEMP_UNIT] ?: UNIT_CELSIUS }
        .stateIn(scope, SharingStarted.Eagerly, UNIT_CELSIUS)

    val isCelsius: Flow<Boolean> = tempUnit.map { it == UNIT_CELSIUS }

    /**
     * 天气预警通知开关。
     *
     * 后台周期任务本身不会因为关闭开关而停止——存在桌面小组件时它仍然需要刷新天气；
     * 关闭后只是不再为预警消耗流量，也不再发出通知。
     */
    val alertNotificationsEnabled: Flow<Boolean> = preferences
        .map { it[KEY_ALERT_NOTIFICATIONS_ENABLED] ?: true }

    suspend fun setTempUnit(unit: Int) {
        require(unit == UNIT_CELSIUS || unit == UNIT_FAHRENHEIT)
        appContext.weatherDataStore.edit { it[KEY_TEMP_UNIT] = unit }
        WeatherWidgetUpdateNotifier.notifyDataChanged(appContext)
    }

    fun setTempUnitAsync(unit: Int) {
        scope.launch { setTempUnit(unit) }
    }

    suspend fun setAlertNotificationsEnabled(enabled: Boolean) {
        appContext.weatherDataStore.edit { it[KEY_ALERT_NOTIFICATIONS_ENABLED] = enabled }
    }

    /** 直接读取持久化温度单位，避免后台任务拿到 [tempUnit] 的初始占位值。 */
    suspend fun readTempUnit(): Int =
        preferences.first()[KEY_TEMP_UNIT] ?: UNIT_CELSIUS

    /** 后台任务使用：预警通知开关。 */
    suspend fun readAlertNotificationsEnabled(): Boolean =
        preferences.first()[KEY_ALERT_NOTIFICATIONS_ENABLED] ?: true

    /**
     * 上一次自动定位的时间戳（墙钟）。
     *
     * 持久化的原因：单调时钟跨重启会归零，内存字段又会在进程回收后丢失，
     * 两者都会让「每次冷启动都能立刻再定位一次」变成默认行为。
     */
    suspend fun readLastLocationAttemptMillis(): Long =
        preferences.first()[KEY_LAST_LOCATION_ATTEMPT] ?: 0L

    suspend fun markLocationAttempt(atMillis: Long) {
        appContext.weatherDataStore.edit { it[KEY_LAST_LOCATION_ATTEMPT] = atMillis }
    }

    /** 定位被取消时清空，保证「取消」不占用节流窗口。 */
    suspend fun clearLocationAttempt() {
        appContext.weatherDataStore.edit { it.remove(KEY_LAST_LOCATION_ATTEMPT) }
    }

    /** 是否已经弹过 Android 13+ 的通知权限请求，避免每次启动重复打扰。 */
    suspend fun readNotificationPermissionRequested(): Boolean =
        preferences.first()[KEY_NOTIFICATION_PERMISSION_REQUESTED] ?: false

    suspend fun markNotificationPermissionRequested() {
        appContext.weatherDataStore.edit {
            it[KEY_NOTIFICATION_PERMISSION_REQUESTED] = true
        }
    }

    /** 一次读取一组小组件的固定城市选择；缺失项表示自动选择。 */
    suspend fun readWidgetCitySelections(appWidgetIds: IntArray): Map<Int, String> {
        if (appWidgetIds.isEmpty()) return emptyMap()
        val snapshot = preferences.first()
        return appWidgetIds.asSequence().mapNotNull { appWidgetId ->
            snapshot[widgetCityKey(appWidgetId)]?.let { appWidgetId to it }
        }.toMap()
    }

    suspend fun setWidgetCitySelection(appWidgetId: Int, cityKey: String) {
        require(appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID)
        appContext.weatherDataStore.edit { preferences ->
            preferences[widgetCityKey(appWidgetId)] = cityKey
        }
    }

    suspend fun removeWidgetCitySelections(appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        appContext.weatherDataStore.edit { preferences ->
            appWidgetIds.forEach { appWidgetId ->
                preferences.remove(widgetCityKey(appWidgetId))
            }
        }
    }

    suspend fun remapWidgetCitySelections(oldIds: IntArray, newIds: IntArray) {
        if (oldIds.isEmpty() || oldIds.size != newIds.size) return
        appContext.weatherDataStore.edit { preferences ->
            oldIds.indices.forEach { index ->
                val oldKey = widgetCityKey(oldIds[index])
                val newKey = widgetCityKey(newIds[index])
                preferences[oldKey]?.let { selection -> preferences[newKey] = selection }
                preferences.remove(oldKey)
            }
        }
    }

    companion object {
        val KEY_TEMP_UNIT = intPreferencesKey("temp_unit")
        private val KEY_ALERT_NOTIFICATIONS_ENABLED = booleanPreferencesKey("alert_notifications_enabled")
        private val KEY_NOTIFICATION_PERMISSION_REQUESTED =
            booleanPreferencesKey("notification_permission_requested")
        private val KEY_LAST_LOCATION_ATTEMPT = longPreferencesKey("last_location_attempt")
        const val UNIT_CELSIUS = 1
        const val UNIT_FAHRENHEIT = 2
        private const val WIDGET_CITY_KEY_PREFIX = "weather_widget_city_"

        @Volatile
        private var instance: WeatherSettings? = null

        fun getInstance(context: Context): WeatherSettings =
            instance ?: synchronized(this) {
                instance ?: WeatherSettings(context).also { instance = it }
            }

        private fun widgetCityKey(appWidgetId: Int) =
            stringPreferencesKey("$WIDGET_CITY_KEY_PREFIX$appWidgetId")
    }
}
