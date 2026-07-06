package com.floatwatch.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

fun roundedBg(
    color: Int,
    radiusDp: Float,
    strokeWidthDp: Int = 0,
    strokeColor: Int = Color.TRANSPARENT,
    view: View? = null
): GradientDrawable {
    val density = view?.resources?.displayMetrics?.density ?: 1f
    return GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * density
        if (strokeWidthDp > 0) setStroke((strokeWidthDp * density).toInt(), strokeColor)
    }
}

fun gradientBg(
    startColor: Int,
    endColor: Int,
    radiusDp: Float,
    view: View? = null,
    orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.LEFT_RIGHT
): GradientDrawable {
    val density = view?.resources?.displayMetrics?.density ?: 1f
    return GradientDrawable(orientation, intArrayOf(startColor, endColor)).apply {
        cornerRadius = radiusDp * density
    }
}

fun TextView.bold() {
    typeface = Typeface.DEFAULT_BOLD
}

fun TextView.medium() {
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
}

fun LinearLayout.addViewWithMargins(
    child: View,
    width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    left: Int = 0,
    top: Int = 0,
    right: Int = 0,
    bottom: Int = 0,
    weight: Float = 0f
) {
    addView(child, LinearLayout.LayoutParams(width, height, weight).apply {
        leftMargin = left
        topMargin = top
        rightMargin = right
        bottomMargin = bottom
    })
}

fun Button.oneUiButton(selected: Boolean) {
    isAllCaps = false
    textSize = 14f
    setTextColor(if (selected) Color.WHITE else Color.rgb(17, 24, 39))
    background = if (selected) {
        gradientBg(Color.rgb(74, 222, 128), Color.rgb(34, 197, 94), 999f, this)
    } else {
        roundedBg(Color.rgb(241, 245, 249), 999f, view = this)
    }
}

fun alphaColor(color: Int, alphaPercent: Int): Int {
    val alpha = (alphaPercent.coerceIn(0, 100) * 255 / 100)
    return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
