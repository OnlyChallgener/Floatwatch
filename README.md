# Floatwatch V2.1 One UI

Android 悬浮秒表 / 悬浮时钟工具。App 显示名：悬浮秒表。包名：`com.floatwatch.app`。

## V2.1 更新

- 新彩色 LOGO：渐变背景 + 悬浮时钟表盘 + 同步状态点。
- 设置界面改成底部弹出 Bottom Sheet，不再固定在底部。
- 设置面板支持：
  - 时钟模式
  - 倒计时模式
  - 时间偏移滑动条（-500ms 到 +500ms）
  - ±10ms 微调
  - 网络延迟显示
  - 立即刷新
  - 自动刷新开关
  - 刷新间隔：3秒 / 5秒 / 10秒
  - 倒计时时长滑动条
  - 快捷倒计时：5秒 / 10秒 / 30秒 / 1分
  - 悬浮窗透明度
  - 悬浮窗大小：小 / 中 / 大
  - 深色 / 浅色主题
- 悬浮小窗 One UI HUD 风格：大圆角、半透明、胶囊延迟标签。
- 悬浮状态关闭方式：
  - 通知栏：显示 / 暂停 / 隐藏 / 停止
  - 悬浮窗长按菜单：精简模式、暂停刷新、隐藏、停止
  - 单击悬浮窗：完整模式 / 精简模式切换
- 拖动后自动吸附左右边缘，并记忆位置。
- GitHub Actions 自动签名、自动构建 APK、自动创建 Release。

## GitHub Secrets

仓库需要以下 Secrets：

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

## 覆盖到现有仓库

假设解压到：

```bash
D:\Projects\Floatwatch_V2_1_OneUI
```

Git Bash：

```bash
cd /d/github/Floatwatch
rm -rf app .github
cp -r /d/Projects/Floatwatch_V2_1_OneUI/* .
cp -r /d/Projects/Floatwatch_V2_1_OneUI/.github .
git status
git add .
git commit -m "Upgrade to Floatwatch V2.1 OneUI"
git push
```

推送后在 GitHub → Actions 等待构建，成功后在 Releases 下载 APK。
