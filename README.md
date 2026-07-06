# Floatwatch V2 OneUI

App 显示名：悬浮秒表  
包名：com.floatwatch.app

## V2 更新

- 悬浮小窗升级为 One UI HUD 风格
- 半透明深色圆角卡片
- 顶部状态点 + 平台名 + 延迟胶囊
- 大号时间数字，适合悬浮观察
- 单击悬浮窗：完整模式 / 精简胶囊模式切换
- 拖动后自动吸附左右边缘
- 按下时轻微透明反馈
- 延迟状态颜色：绿 / 橙 / 红 / 灰
- 保持 V1 的平台时间、HTTP 延迟、5s/10s 刷新、前台服务、自动 Release CI

## 覆盖建议

覆盖旧项目时建议先删除旧的 app 和 .github，避免旧 Kotlin 文件残留：

```bash
cd /d/github/Floatwatch
rm -rf app .github
cp -r /d/Projects/Floatwatch_V2_OneUI/* .
cp -r /d/Projects/Floatwatch_V2_OneUI/.github .
git add .
git commit -m "Upgrade to Floatwatch V2 OneUI"
git push
```

GitHub Secrets 沿用原来的：

- KEYSTORE_BASE64
- KEYSTORE_PASSWORD
- KEY_ALIAS
- KEY_PASSWORD

推送后 Actions 会自动生成签名 APK 并创建 Release。
