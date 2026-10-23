package com.smartisan.weather.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.smartisan.weather.ui.alert.WeatherAlertActivity
import com.smartisan.weather.R
import com.smartisan.weather.data.model.AlertInfo
import com.smartisan.weather.data.model.SavedCity
import com.smartisan.weather.data.model.Weather
import org.json.JSONArray
import org.json.JSONObject

/**
 * 天气预警通知。
 *
 * 前台刷新和后台巡检都会调用 [notifyIfNeeded]；同一条预警按城市 + 服务端预警 ID
 * 去重（旧数据没有 ID 时退化为类型、发布时间与内容），去重记录保留 7 天。
 */
object WeatherAlertNotifier {

    private const val CHANNEL_ID = "weather_alert"

    private const val PREFS_NAME = "weather_alert_notification"
    private const val KEY_NOTIFIED_ALERTS = "notified_alerts_v2"

    private const val MAX_HISTORY = 200
    private const val HISTORY_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L

    /**
     * 连续多少次刷新都看不到该预警才撤销通知。
     *
     * 取 2 而不是 1，是为了容忍服务端偶发漏字段或短暂返回空列表的情况：
     * 一次缺失很可能不是真的解除，两次都缺失才认为是解除。
     */
    private const val ALERT_REMOVAL_CONFIRMATIONS = 2

    fun notifyIfNeeded(
        context: Context,
        city: SavedCity,
        weather: Weather,
    ) {
        // 通知被关闭或没有权限时既不发也不撤：用户看不到，撤销没有意义。
        if (!canPostNotifications(context)) {
            return
        }

        createNotificationChannel(context)

        val prefs = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val records = readNotifiedAlerts(prefs)
        val now = System.currentTimeMillis()
        val alerts = weather.alert.infos
            .filter { it.content.isNotBlank() }
        val currentKeys = alerts.mapTo(mutableSetOf()) {
            buildAlertKey(
                city = city,
                alert = it,
            )
        }
        var changed = false

        // 预警解除：仍在本城历史里、但本次响应已不存在的预警，连续多次缺失再撤销。
        val cityPrefix = "${city.locationKey}|"
        val removedKeys = mutableListOf<String>()
        records.entries.toList().forEach { (key, record) ->
            if (!key.startsWith(cityPrefix)) return@forEach
            if (key in currentKeys) {
                if (record.misses != 0) {
                    records[key] = record.copy(misses = 0)
                    changed = true
                }
                return@forEach
            }
            val misses = record.misses + 1
            changed = true
            if (misses >= ALERT_REMOVAL_CONFIRMATIONS) {
                NotificationManagerCompat.from(context).cancel(key.hashCode())
                records.remove(key)
                removedKeys += key
            } else {
                records[key] = record.copy(misses = misses)
            }
        }

        // 该城市已经没有预警时，连同分组摘要一起撤销。
        if (currentKeys.isEmpty() && removedKeys.isNotEmpty()) {
            NotificationManagerCompat.from(context).cancel(summaryNotificationId(city))
        }

        val newlyNotified = mutableListOf<AlertInfo>()
        alerts.forEach { alert ->
            val key = buildAlertKey(
                city = city,
                alert = alert,
            )

            // 已经通知过
            if (records.containsKey(key)) {
                return@forEach
            }

            showNotification(
                context = context,
                city = city,
                weather = weather,
                alert = alert,
            )

            records[key] = AlertRecord(
                notifiedAt = now,
                misses = 0,
            )
            newlyNotified += alert
            changed = true
        }

        // 同城多条新预警合并成一个摘要，避免一次刷新刷满通知栏。
        if (newlyNotified.size > 1) {
            showGroupSummary(
                context = context,
                city = city,
                weather = weather,
                alerts = newlyNotified,
            )
        }

        if (changed) {
            writeNotifiedAlerts(prefs, records)
        }
    }

    /** 应用通知权限或预警渠道被关闭时，后台巡检直接跳过。 */
    fun canPostNotifications(context: Context): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun buildAlertKey(
        city: SavedCity,
        alert: AlertInfo,
    ): String {
        // 等级变化（如黄色升级为橙色）必须重新通知，因此 ID 之外还要带上等级。
        val identity = if (alert.alertId.isNotBlank()) {
            "${alert.alertId}|${alert.levelNumber}"
        } else {
            listOf(
                alert.type,
                alert.level,
                alert.publishTime,
                alert.content,
            ).joinToString("|")
        }
        return "${city.locationKey}|$identity"
    }

    /** 已通知预警的历史记录；`misses` 表示连续多少次刷新没再看到它。 */
    private data class AlertRecord(
        val notifiedAt: Long,
        val misses: Int,
    )

    private fun readNotifiedAlerts(prefs: SharedPreferences): MutableMap<String, AlertRecord> {
        val raw = prefs.getString(KEY_NOTIFIED_ALERTS, null) ?: return mutableMapOf()
        return runCatching {
            val array = JSONArray(raw)
            buildMap {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val key = item.optString("key")
                    if (key.isNotBlank()) {
                        put(
                            key,
                            AlertRecord(
                                notifiedAt = item.optLong("notifiedAt"),
                                // 旧版本记录没有该字段，按「刚通知过」处理。
                                misses = item.optInt("misses", 0),
                            ),
                        )
                    }
                }
            }.toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    private fun writeNotifiedAlerts(
        prefs: SharedPreferences,
        records: Map<String, AlertRecord>,
    ) {
        val cutoff = System.currentTimeMillis() - HISTORY_TTL_MILLIS
        val array = JSONArray()
        records.entries
            .filter { it.value.notifiedAt >= cutoff }
            .sortedByDescending { it.value.notifiedAt }
            .take(MAX_HISTORY)
            .forEach { (key, record) ->
                array.put(
                    JSONObject()
                        .put("key", key)
                        .put("notifiedAt", record.notifiedAt)
                        .put("misses", record.misses),
                )
            }
        prefs.edit()
            .putString(KEY_NOTIFIED_ALERTS, array.toString())
            .apply()
    }

    private fun createNotificationChannel(
        context: Context,
    ) {
        val manager =
            context.getSystemService(
                NotificationManager::class.java,
            )

        if (
            manager.getNotificationChannel(CHANNEL_ID) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.weather_alert_title),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(
                        R.string.weather_alert_notification_channel_description,
                    )
                },
            )
        }
    }

    /**
     * 只在 [notifyIfNeeded] 通过 [canPostNotifications] 校验后调用。
     * Lint 无法跨方法关联权限检查，这里显式标注。
     */
    @SuppressLint("MissingPermission")
    private fun showNotification(
        context: Context,
        city: SavedCity,
        weather: Weather,
        alert: AlertInfo,
    ) {
        val notificationId = buildNotificationId(
            city = city,
            alert = alert,
        )

        val notification = NotificationCompat.Builder(
            context,
            CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_stat_weather_alert)
            .setContentTitle(
                buildString {
                    append(city.locationName)
                    append(" · ")
                    append(
                        context.getString(
                            R.string.weather_alert_tip,
                            alert.type,
                            alert.level,
                        ),
                    )
                },
            )
            .setContentText(alert.content)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(alert.content)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setShowWhen(true)
            .setWhen(alert.publishTime.takeIf { it > 0L } ?: System.currentTimeMillis())
            .setGroup(groupKey(city))
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(alertContentIntent(context, weather, notificationId))
            .build()

        NotificationManagerCompat.from(context).notify(
            notificationId,
            notification,
        )
    }

    /** 同城多条新预警只额外发一条摘要，点击后仍展示完整预警列表。 */
    @SuppressLint("MissingPermission")
    private fun showGroupSummary(
        context: Context,
        city: SavedCity,
        weather: Weather,
        alerts: List<AlertInfo>,
    ) {
        val notificationId = summaryNotificationId(city)

        val notification = NotificationCompat.Builder(
            context,
            CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_stat_weather_alert)
            .setContentTitle(
                context.getString(
                    R.string.weather_alert_notification_summary,
                    city.locationName,
                    alerts.size,
                ),
            )
            .setContentText(alerts.first().content)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(alerts.joinToString("\n") { it.content }),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setGroup(groupKey(city))
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setAutoCancel(true)
            .setContentIntent(alertContentIntent(context, weather, notificationId))
            .build()

        NotificationManagerCompat.from(context).notify(
            notificationId,
            notification,
        )
    }

    private fun alertContentIntent(
        context: Context,
        weather: Weather,
        notificationId: Int,
    ): PendingIntent {
        val intent = Intent(
            context,
            WeatherAlertActivity::class.java,
        ).apply {
            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(
                WeatherAlertActivity.EXTRA_ALERT,
                weather.alert,
            )
        }

        return PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun groupKey(city: SavedCity): String =
        "weather_alert_${city.locationKey}"

    private fun summaryNotificationId(city: SavedCity): Int =
        "${city.locationKey}|summary".hashCode()

    private fun buildNotificationId(
        city: SavedCity,
        alert: AlertInfo,
    ): Int = buildAlertKey(city, alert).hashCode()
}