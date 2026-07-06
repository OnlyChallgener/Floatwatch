# Floatwatch V2.2 Compact

本版重点：紧凑底部弹窗、两步开启悬浮、毫秒显示统一到 0.1 秒、倒计时预设和结束时间逻辑修正。

## 更新点

- 首页底部拆分为：`设置` 和 `开启悬浮时钟`
- 首页点击 `开启悬浮时钟` 只弹出悬浮配置，不直接启动
- 弹窗底部再次点击 `开启悬浮时钟` 才真正创建悬浮窗
- 悬浮设置页参考紧凑卡片：字更小、按钮更小、行距更紧凑
- 时钟模式 / 倒计时模式分开显示，不把全部内容堆在一起
- 时间偏移统一为：`提前 / 延后 + 0~1000ms`
- 完整模式和精简模式都显示到小数点后一位，例如 `13:41:08.2`
- 倒计时预设改为：`30s / 1min / 3min / 5min`
- 倒计时结束时间支持输入，例如 `12:00:00.0`
- 倒计时优先级：结束时间 > 预设时间
- 完整悬浮窗去掉底部“长按提示”文字
- 长按 1 秒才显示悬浮菜单，短按只切换完整/精简
- 通知点击可回到 App
- 增加华为卡片：`https://www.vmall.com/`

## 覆盖方式

```bash
cd /d/github/Floatwatch
rm -rf app .github
cp -r /d/Projects/Floatwatch_V2_2_Compact/* .
cp -r /d/Projects/Floatwatch_V2_2_Compact/.github .
git add .
git commit -m "Upgrade to Floatwatch V2.2 compact sheet"
git push
```

GitHub Actions 会自动构建签名 APK 并创建 Release。
