# Floatwatch V2.5.1 抢购精准引擎

更新重点：
- 顶部大卡片右下角增加纯刷新按钮，手动刷新全部平台延迟并触发一次时间校准。
- 所有平台延迟持续自动刷新，不再只刷新当前选中平台。
- 平台延迟每个平台独立请求序号和独立平滑状态，避免旧请求覆盖新结果、避免平台之间互相污染。
- 时间显示改为 TimeKeeper：NTP 校准 + elapsedRealtime 推进。NTP 失败时自动回退系统时间。
- 主页卡片压回紧凑高度，靠固定 ms 行高和 padding 修复裁切，不再盲目加高。
- 平台圆字改成可替换图片图标，文件在 app/src/main/res/drawable-nodpi/：
  - ic_platform_system.png
  - ic_platform_jd.png
  - ic_platform_taobao.png
  - ic_platform_pdd.png
  - ic_platform_huawei.png
  - ic_platform_xiaomi.png
  - ic_platform_douyin.png
  - ic_platform_wechat.png
  - ic_platform_amazon.png
  你可以用同名 PNG 覆盖这些文件再 push，代码不用改。


V2.5.1：首页所有平台卡片持续自动刷新；顶部刷新按钮刷新全部平台并校准时间。
