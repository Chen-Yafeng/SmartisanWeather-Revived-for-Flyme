package com.smartisan.weather.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 后台周期刷新的唯一入口。
 *
 * 桌面小组件与天气预警巡检共用同一条周期任务：两者都需要“拉取已保存城市的天气”，
 * 而 [com.smartisan.weather.data.weather.WeatherRepository.fetchWeather] 在成功时
 * 已经同时写入了缓存并完成预警通知去重，拆成两条周期任务只会重复请求同一批城市。
 *
 * 具体刷新哪些内容由 [WeatherRefreshWorker] 在运行时判断（是否存在小组件、
 * 通知是否可用），这里只负责保证任务存在且参数一致。
 */
internal object WeatherRefreshScheduler {

    /**
     * 幂等入队。冷启动、添加/更新小组件、配置完成后调用都不会产生重复任务；
     * 系统清理或覆盖安装后也能重新补齐调度。
     */
    fun ensureScheduled(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        cancelLegacyWork(workManager)
        val request = PeriodicWorkRequestBuilder<WeatherRefreshWorker>(
            INTERVAL_MINUTES,
            TimeUnit.MINUTES,
            FLEX_MINUTES,
            TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            // Worker 整轮失败时返回 retry；周期任务的最小指数退避是 10 分钟。
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_MINUTES,
                TimeUnit.MINUTES,
            )
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * 合并前小组件与预警各自持有一条 periodic work。必须显式取消，
     * 否则已安装设备上会同时存在三条周期任务，同一批城市被重复拉取。
     */
    private fun cancelLegacyWork(workManager: WorkManager) {
        if (legacyWorkCleared) return
        legacyWorkCleared = true
        LEGACY_WORK_NAMES.forEach(workManager::cancelUniqueWork)
    }

    const val WORK_TAG = "weather-periodic-refresh"

    private const val WORK_NAME = "weather-periodic-refresh"

    // WorkManager 周期任务最小间隔是 15 分钟。30 分钟在时效与流量/耗电之间取平衡，
    // 也是合并前小组件与预警两条链路共同使用的周期。
    private const val INTERVAL_MINUTES = 30L
    private const val FLEX_MINUTES = 10L

    /** 周期任务退避下限：WorkManager 要求 10 分钟起。 */
    private const val BACKOFF_MINUTES = 10L

    private val LEGACY_WORK_NAMES = listOf(
        "weather-widget-periodic-refresh",
        "weather-alert-periodic-check",
    )

    @Volatile
    private var legacyWorkCleared = false
}
