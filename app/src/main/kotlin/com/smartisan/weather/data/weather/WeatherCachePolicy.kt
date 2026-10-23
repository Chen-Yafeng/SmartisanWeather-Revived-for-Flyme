package com.smartisan.weather.data.weather

/**
 * 自动刷新的唯一判定标准，前台页面与后台周期任务共用。
 *
 * 缓存时间戳由 [com.smartisan.weather.data.city.CityRepository.cacheWeather] 写入 Room，
 * 因此无需额外保存「最近一次请求时间」：前台刚刷新过的城市，后台巡检能直接看到并跳过，
 * 反向同样成立。用户主动刷新不走这个判定。
 */
internal const val AUTOMATIC_WEATHER_REFRESH_INTERVAL_MILLIS = 10L * 60L * 1_000L

/**
 * 是否需要为自动刷新重新请求网络。
 *
 * `updatedAtMillis <= 0` 视为从未缓存；时间戳落在未来（写过系统时间）同样视为不可信，
 * 两种情况都要求重新请求，避免负数时间差被误判成新鲜缓存。
 */
internal fun shouldRefreshWeatherCache(
    updatedAtMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
): Boolean {
    if (updatedAtMillis <= 0L) return true
    val ageMillis = nowMillis - updatedAtMillis
    return ageMillis !in 0L until AUTOMATIC_WEATHER_REFRESH_INTERVAL_MILLIS
}
