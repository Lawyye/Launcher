package com.lawyye.wired

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density

fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.monoText(sizeSp: Float, color: Int = Palette.TEXT, bold: Boolean = false): TextView {
    val tv = TextView(this)
    tv.typeface = if (bold) Palette.monoBold else Palette.mono
    tv.textSize = sizeSp
    tv.setTextColor(color)
    tv.letterSpacing = 0.06f
    tv.includeFontPadding = false
    return tv
}

fun Context.toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

fun pressBg(accent: Int): StateListDrawable {
    val d = StateListDrawable()
    d.addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Palette.withAlpha(accent, 0x38)))
    d.addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    return d
}

@Suppress("DEPRECATION")
fun systemInsets(insets: WindowInsets, withIme: Boolean = false): Rect {
    return if (Build.VERSION.SDK_INT >= 30) {
        var mask = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        if (withIme) mask = mask or WindowInsets.Type.ime()
        val i = insets.getInsets(mask)
        Rect(i.left, i.top, i.right, i.bottom)
    } else {
        Rect(
            insets.systemWindowInsetLeft,
            insets.systemWindowInsetTop,
            insets.systemWindowInsetRight,
            insets.systemWindowInsetBottom,
        )
    }
}

/** Draw behind status and navigation bars; content is padded with the real insets instead. */
@Suppress("DEPRECATION")
fun Activity.edgeToEdge() {
    val w = window
    if (Build.VERSION.SDK_INT >= 30) {
        w.setDecorFitsSystemWindows(false)
    } else {
        w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
    w.statusBarColor = Color.TRANSPARENT
    w.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= 29) {
        w.isStatusBarContrastEnforced = false
        w.isNavigationBarContrastEnforced = false
    }
    if (Build.VERSION.SDK_INT >= 28) {
        val lp = w.attributes
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        w.attributes = lp
    }
}
