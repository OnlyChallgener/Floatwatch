package com.floatwatch.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView

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
        if (strokeWidthDp > 0) {
            setStroke((strokeWidthDp * density).toInt(), strokeColor)
        }
    }
}

fun TextView.bold() {
    typeface = Typeface.DEFAULT_BOLD
}
