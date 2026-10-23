package com.smartisan.weather.ui.main

/**
 * 自动定位间隔，与天气缓存的新鲜度阈值（`AUTOMATIC_WEATHER_REFRESH_INTERVAL_MILLIS`）保持一致。
 *
 * 两者语义相同：距离上一次真实完成的工作不到 10 分钟，就没必要再打扰系统和网络。
 */
internal const val AUTOMATIC_LOCATION_INTERVAL_MILLIS = 10L * 60L * 1_000L

/**
 * 是否需要为自动定位重新取一次坐标。
 *
 * 这里用墙钟（`System.currentTimeMillis()`）而不是单调时钟，因为上次尝试时间会持久化到
 * DataStore，必须跨进程重启有效。时间戳落在未来（写过系统时间）视为不可信，要求重新定位。
 */
internal fun shouldRefreshLocation(lastAttemptMillis: Long?, nowMillis: Long): Boolean {
    if (lastAttemptMillis == null || lastAttemptMillis <= 0L) return true
    val ageMillis = nowMillis - lastAttemptMillis
    return ageMillis !in 0L until AUTOMATIC_LOCATION_INTERVAL_MILLIS
}
