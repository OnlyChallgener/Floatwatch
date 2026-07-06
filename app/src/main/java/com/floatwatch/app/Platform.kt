package com.floatwatch.app

data class Platform(
    val name: String,
    val shortName: String,
    val url: String?,
    val color: Int
)

object Platforms {
    val items = listOf(
        Platform("系统时间", "系", null, 0xFF111827.toInt()),
        Platform("京东", "京", "https://www.jd.com/", 0xFFE1251B.toInt()),
        Platform("淘宝", "淘", "https://www.taobao.com/", 0xFFFF6A00.toInt()),
        Platform("拼多多", "拼", "https://mobile.yangkeduo.com/", 0xFFE02E24.toInt()),
        Platform("华为", "华", "https://www.vmall.com/", 0xFFCF0A2C.toInt()),
        Platform("小米", "米", "https://www.mi.com/", 0xFFFF6900.toInt()),
        Platform("抖音", "抖", "https://www.douyin.com/", 0xFF111827.toInt()),
        Platform("微信", "微", "https://weixin.qq.com/", 0xFF0EA75A.toInt()),
        Platform("亚马逊", "亚", "https://www.amazon.com/", 0xFFF59E0B.toInt())
    )
}
