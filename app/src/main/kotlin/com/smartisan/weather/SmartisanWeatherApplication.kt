package com.smartisan.weather

import android.app.Application
import android.content.res.Configuration
import com.smartisan.weather.appwidget.WeatherWidgetScheduler
import com.smartisan.weather.appwidget.WeatherWidgetUpdater
import com.smartisan.weather.background.WeatherRefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * 天气应用不需要复杂的全局初始化，Room 和 DataStore 都在各自的单例中懒加载。
 */
class SmartisanWeatherApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nightMode = Configuration.UI_MODE_NIGHT_NO

    override fun onCreate() {
        super.onCreate()
        nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        // 周期任务入队是幂等的；每次冷启动补齐调度，覆盖安装或系统清理后也能恢复。
        // 预警巡检与小组件刷新共用这一条任务，同时会清掉旧版本遗留的两条周期任务。
        appScope.launch {
            WeatherRefreshScheduler.ensureScheduled(this@SmartisanWeatherApplication)
        }
    }

    /**
     * 小组件背景在夜间要盖一层遮罩。遮罩本身是 `-night` 限定的资源，桌面进程重新应用
     * RemoteViews 时会自己取到当前配置；这里额外补一次重绘，覆盖不主动重应用 RemoteViews
     * 的桌面，同时让内容（更新时间、图标等）跟着当前配置走。
     *
     * 注意系统只在进程收到配置变更时回调这里。进程已被回收时不会有任何回调——
     * Android 没有能被静态注册收到的深色模式广播，那种情况只能等下一次常规刷新。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val current = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (current == nightMode) return
        nightMode = current
        if (!WeatherWidgetScheduler.hasWidgets(this)) return
        // 直接重绘而不是丢给 WorkManager：一次性任务的调度可能被推迟数分钟，
        // 用户会以为深色模式没有生效。
        appScope.launch {
            WeatherWidgetUpdater.renderCached(this@SmartisanWeatherApplication)
        }
    }
}
