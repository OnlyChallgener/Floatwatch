# Floatwatch / 悬浮秒表

V1 稳定工程包。

## 功能

- App 显示名：悬浮秒表
- 包名：`com.floatwatch.app`
- 悬浮窗：`SYSTEM_ALERT_WINDOW + WindowManager + TYPE_APPLICATION_OVERLAY`
- 前台服务保活
- 悬浮窗可拖动
- 单击悬浮窗切换精简模式
- 时钟模式 / 秒表模式
- 平台 HTTP 延迟测试
- HTTP Date 时间偏移估算
- 5s / 10s 自动刷新
- GitHub Actions 自动构建、签名、发布 Release

## 签名文件

不要提交 `.jks` 和 `keystore.properties`。

GitHub Secrets 需要：

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

## 本包构建组合

- Android Gradle Plugin: 8.5.2
- Kotlin: 1.9.24
- Gradle: 8.9（由 GitHub Actions 安装）
- JDK: 17
- compileSdk: 35
- targetSdk: 33

## GitHub Actions

push 到 main 后自动：

1. 编译 release APK
2. 使用 Secrets 中的 keystore 签名
3. 校验 APK 签名
4. 上传 Artifact
5. 创建 GitHub Release
