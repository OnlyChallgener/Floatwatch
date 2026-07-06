# Floatwatch V1 / 悬浮秒表

V1 是一个最小可运行 Android 工程：悬浮时钟 + 秒表 + 平台 HTTP 延迟刷新。

## 功能

- App 名称：悬浮秒表
- 工程名：Floatwatch
- 包名：com.floatwatch.app
- App 内不显示标题栏
- 系统时间 / 平台时间卡片
- HTTP HEAD/GET 轻量测延迟
- 默认每 5 秒刷新，支持切换 5s / 10s
- 手动偏移：-50ms / +50ms / 重置
- 悬浮窗权限申请
- WindowManager + TYPE_APPLICATION_OVERLAY
- 前台服务保活
- 悬浮窗可拖动
- 单击悬浮窗切换精简模式
- 时钟模式 / 秒表模式

## 运行方法

1. 用 Android Studio 打开本目录 `Floatwatch_V1`
2. 等待 Gradle Sync 完成
3. 连接 Android 手机，点击 Run
4. 首次打开后点击「权限」，授予“显示在其他应用上层”
5. 回到 App，点击「开启悬浮」

## 重要说明

- V1 的“平台时间”优先读取 HTTP 响应头里的 `Date` 字段。
- 有些平台会拒绝 HEAD 请求，代码会自动尝试 GET。
- 如果平台没有返回 Date，时间显示会回退到系统本地时间。
- 延迟是 HTTP 请求耗时，不是 ICMP Ping。
- targetSdk 暂设为 33，方便自用测试悬浮窗和前台服务。
