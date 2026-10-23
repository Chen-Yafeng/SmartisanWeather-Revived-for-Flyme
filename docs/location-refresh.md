# 定位与天气刷新

## 设计

- Activity 只处理权限、系统设置、导航和前台边界。首次说明同意后才创建天气 ViewModel；权限/设置页面返回遇到进程重建时，先读取 DataStore 的同意状态。
- 同时声明和申请 `ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION`。粗略定位是完整可用的分支。首次拒绝不循环索要权限，再次操作提供解释；无法再弹系统权限框时提供应用设置入口。
- 搜索页的定位项始终可以重新定位，即使该城市已添加。只有用户明确操作定位项时才提示提高精度；日常刷新与自动更新不会反复索要精确权限。
- ViewModel 持有坐标获取、城市反查、Room 写入和天气刷新的任务。定位开始立即进入加载态，Activity 停止时取消任务；配置变化保留 ViewModel 任务。取消传递到系统 CancellationSignal，不发送失败提示、不启动补偿请求。
- 不再直接读取 `getLastKnownLocation`。支持的平台使用带质量配置的系统 `getCurrentLocation`，旧平台使用 AndroidX 兼容 API。融合、网络及有精确权限时的 GPS 并发请求，取消剩余请求后才返回；不主动请求 passive provider。
- 检查坐标范围、单调时钟年龄和水平精度。当前策略为总等待 20 秒，坐标不超过 30 秒；精确授权下，首个结果超过 200 米时最多再等 4 秒以争取更好的结果。粗略授权直接使用首个有效结果。这些数字是应用策略，不是 Android 对精度或耗时的保证。
- 手动定位先立即刷新已有定位城市的天气，再开始取坐标：坐标最多要等 20 秒，用户点下去应当马上看到反应。定位成功且落在同一座城市时不再重复请求（缓存刚写入），落到别的城市才刷新新城市。同时写入定位城市时不再顺带入队一次小组件刷新，前台自己会请求并重绘。定位失败仍刷新已保存定位城市的天气，且不会因为刚刷过而重复请求，失败不删除缓存。
- 已保存定位城市且权限、定位开关正常时，冷启动与回前台自动重新定位，间隔至少 10 分钟（与天气缓存阈值一致）。节流时间戳写入 DataStore 而不是内存：`elapsedRealtime` 跨重启会归零，内存字段又会随进程回收丢失，两种都会让「每次冷启动都能立刻再定位」变成默认行为；时间戳落在未来（用户改过系统时间）视为不可信，允许重新定位。定位被取消时清空时间戳，取消不占用节流窗口。没有定位城市时不做自动定位（用户可能刚删掉它，不应该自己长回来）。自动失败不弹窗、不抢城市焦点。不申请后台定位权限，不增加持续追踪服务。
- 桌面小组件显示定位城市时，点「刷新」不再走后台 Worker：由不可见的透明 Activity 复用前台定位权限执行一次完整定位，然后写入定位城市、请求天气并重绘组件。等待上限 8 秒，比前台定位的 20 秒更短，因为这是用户主动触发的动作。无权限、定位开关关闭、没有 fix、城市反查失败或超时都回退为「按已保存的城市 Key 刷新」，用户点下去一定有反馈，组件也不会长期停在「刷新中」。该透明页无窗口动画、`taskAffinity` 置空且不入最近任务，因此不会把主界面任务拉到前台；它同样不需要 `ACCESS_BACKGROUND_LOCATION`。页面销毁不取消这段任务（`noHistory` 会让用户回桌面时立即销毁页面），否则组件状态无法收尾。
- Debug 日志仅记录授权粒度、provider、精度半径和耗时，不记录设备坐标或完整 API URL。

## 官方依据

核对日期：2026-09-10。保持系统 LocationManager 链路，不把 Google Play services 的独立定位客户端误当成系统 FUSED_PROVIDER。

- [运行时定位权限](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime)（页面更新于 2026-09-08）：联合申请、粗略分支、精度升级，以及降级导致进程重启。
- [权限请求规范](https://developer.android.com/training/permissions/requesting)：按场景申请、解释原因、拒绝后的功能降级。
- [LocationManager](https://developer.android.com/reference/android/location/LocationManager) 与 [LocationRequest.Builder](https://developer.android.com/reference/android/location/LocationRequest.Builder)：一次定位、请求质量、CancellationSignal。
- [LocationManagerCompat](https://developer.android.com/reference/androidx/core/location/LocationManagerCompat)：当前定位语义、取消与前台调用。
- [定位权限测试指南](https://developer.android.com/develop/sensors-and-location/location/testing)：粗略授权、设置页精度降级与升级。

## 共享弹窗

定位权限、精度升级、定位服务关闭和首次说明都复用 `WeatherNoticeDialog`。窗口的原始 NinePatch 并不会自动裁剪 Compose 子项；内容根节点补上与原按钮圆角一致的 12dp 裁剪，避免标题栏和按钮栏的矩形背景盖住四角。搜索页不再保留独立权限弹窗。

## 验证范围

- JVM：前台定位节流的首次请求、边界时间、快速切换、未来时间戳与 10 分钟常量。
- 设备自动测试：融合源不响应时网络源不受阻、GPS 改善精度、粗略授权不等待精确坐标、过期/未来/无效坐标拒绝、总超时、精度等待窗口、取消全部来源。
- Compose：精度提示保留粗略定位选项，权限拒绝可取消，已有定位城市可重新定位。
- 本次定位重构的 JVM 41 项、Android 17 设备测试 39 项通过，Debug / Lint / R8 Release 构建通过。realme Android 16 上验证了旧安装升级后的精确权限授予，以及同一城市手动刷新确实更新天气缓存。Android 17 验证了系统精确/粗略选项、拒绝后手动选城与再次申请。
- 最后的共享圆角修复遵照用户要求，仅做 Debug 构建与截图核对，没有再次运行整套测试。真实移动、区县边界和不同厂商室内定位仍需后续路线回归；城市归属由小米坐标反查决定。
