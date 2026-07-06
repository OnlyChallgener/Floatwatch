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
        Platform("小米", "米", "https://www.mi.com/", 0xFFFF6900.toInt()),
        Platform("百度", "百", "https://www.baidu.com/", 0xFF2563EB.toInt()),
        Platform("B站", "B", "https://www.bilibili.com/", 0xFF00A1D6.toInt()),
        Platform("Cloudflare", "C", "https://www.cloudflare.com/", 0xFFF38020.toInt())
    )
}
