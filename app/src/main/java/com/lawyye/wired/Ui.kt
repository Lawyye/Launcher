package com.lawyye.wired

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density

fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.dpi(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

// ---------------------------------------------------------------- text

fun Context.label(sizeSp: Float, color: Int, face: Typeface = Fonts.ui): TextView {
    val tv = TextView(this)
    tv.typeface = face
    tv.textSize = sizeSp
    tv.setTextColor(color)
    tv.includeFontPadding = false
    return tv
}

/** Monospace metadata: always lowercase, small, quiet. */
fun Context.meta(text: String, sizeSp: Float = 11f, color: Int = Wired.TEXT2): TextView {
    val tv = label(sizeSp, color, Fonts.mono)
    tv.text = text
    return tv
}

fun TextView.singleLine(): TextView {
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
    return this
}

// ---------------------------------------------------------------- shapes

fun Context.rounded(fill: Int, radiusDp: Float, stroke: Int = 0, strokeDp: Float = 1f): GradientDrawable {
    val g = GradientDrawable()
    g.shape = GradientDrawable.RECTANGLE
    g.cornerRadius = dp(radiusDp)
    g.setColor(fill)
    if (stroke != 0) g.setStroke(dpi(strokeDp).coerceAtLeast(1), stroke)
    return g
}

fun Context.oval(fill: Int, sizeDp: Float = 0f): GradientDrawable {
    val g = GradientDrawable()
    g.shape = GradientDrawable.OVAL
    g.setColor(fill)
    if (sizeDp > 0f) g.setSize(dpi(sizeDp), dpi(sizeDp))
    return g
}

/** Soft press highlight in the accent colour, clipped to the same rounded shape. */
fun Context.ripple(content: Drawable?, radiusDp: Float, accent: Int = Wired.BLUE): RippleDrawable {
    val mask = rounded(Color.WHITE, radiusDp)
    return RippleDrawable(ColorStateList.valueOf(Wired.alpha(accent, 0x30)), content, mask)
}

fun Context.icon(res: Int, color: Int): Drawable? {
    val d = getDrawable(res)?.mutate() ?: return null
    d.setTint(color)
    return d
}

fun Context.iconView(res: Int, color: Int, sizeDp: Int): ImageView {
    val iv = ImageView(this)
    iv.setImageDrawable(icon(res, color))
    iv.scaleType = ImageView.ScaleType.FIT_CENTER
    iv.layoutParams = LinearLayout.LayoutParams(dpi(sizeDp), dpi(sizeDp))
    iv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    return iv
}

// ---------------------------------------------------------------- controls

/** 44dp square button with a thin border, used in headers. */
fun Context.iconButton(res: Int, description: String, onClick: () -> Unit): FrameLayout {
    val f = FrameLayout(this)
    f.background = ripple(rounded(0x99101827.toInt(), 14f, Wired.LINE), 14f)
    f.contentDescription = description
    f.isClickable = true
    f.isFocusable = true
    val iv = ImageView(this)
    iv.setImageDrawable(icon(res, Wired.ICON_SOFT))
    iv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    f.addView(iv, FrameLayout.LayoutParams(dpi(20), dpi(20), Gravity.CENTER))
    f.setOnClickListener { onClick() }
    f.layoutParams = LinearLayout.LayoutParams(dpi(44), dpi(44))
    return f
}

fun Context.primaryButton(text: String, accent: Int, onClick: () -> Unit): TextView {
    val b = label(15f, Wired.VOID, Fonts.uiBold)
    b.text = text
    b.gravity = Gravity.CENTER
    b.background = ripple(rounded(accent, 16f), 16f, Color.WHITE)
    b.minHeight = dpi(48)
    b.setPadding(dpi(20), 0, dpi(20), 0)
    b.isClickable = true
    b.isFocusable = true
    b.setOnClickListener { onClick() }
    return b
}

fun Context.ghostButton(text: String, onClick: () -> Unit): TextView {
    val b = label(14f, 0xFFCFE3FF.toInt(), Fonts.uiSemi)
    b.text = text
    b.gravity = Gravity.CENTER
    b.background = ripple(rounded(Color.TRANSPARENT, 14f, 0x4D82B9FF), 14f)
    b.minHeight = dpi(44)
    b.setPadding(dpi(18), 0, dpi(18), 0)
    b.isClickable = true
    b.isFocusable = true
    b.setOnClickListener { onClick() }
    return b
}

/** Filter chip. Call [styleChip] to switch between selected and idle. */
fun Context.chip(text: String, selected: Boolean, accent: Int, onClick: () -> Unit): TextView {
    val c = label(13f, Wired.CHIP_TEXT, Fonts.ui)
    c.text = text
    c.gravity = Gravity.CENTER
    c.minHeight = dpi(36)
    c.setPadding(dpi(14), 0, dpi(14), 0)
    c.isClickable = true
    c.isFocusable = true
    c.setOnClickListener { onClick() }
    styleChip(c, selected, accent)
    return c
}

fun Context.styleChip(c: TextView, selected: Boolean, accent: Int) {
    c.isSelected = selected
    if (selected) {
        c.background = ripple(rounded(Wired.alpha(accent, 0x24), 12f, Wired.alpha(accent, 0x73)), 12f, accent)
        c.setTextColor(0xFFE6F1FF.toInt())
        c.typeface = Fonts.uiSemi
    } else {
        c.background = ripple(rounded(Color.TRANSPARENT, 12f, Wired.LINE), 12f, accent)
        c.setTextColor(Wired.CHIP_TEXT)
        c.typeface = Fonts.ui
    }
}

/** Segmented control: equal-width options inside a dark track. */
class Segmented(
    ctx: Context,
    private val options: List<String>,
    selected: Int,
    private val accent: Int,
    private val onPick: (Int) -> Unit,
) : LinearLayout(ctx) {

    private val items = ArrayList<TextView>()
    var selected: Int = selected
        private set

    init {
        orientation = HORIZONTAL
        background = ctx.rounded(Wired.SEGMENT_TRACK, 14f, Wired.LINE_SOFT)
        val p = ctx.dpi(4)
        setPadding(p, p, p, p)
        options.forEachIndexed { i, text ->
            val t = ctx.label(14f, Wired.CHIP_TEXT, Fonts.ui)
            t.text = text
            t.gravity = Gravity.CENTER
            t.isClickable = true
            t.isFocusable = true
            t.setOnClickListener {
                if (this.selected != i) {
                    select(i)
                    onPick(i)
                }
            }
            val lp = LayoutParams(0, ctx.dpi(44), 1f)
            if (i > 0) lp.leftMargin = ctx.dpi(4)
            addView(t, lp)
            items.add(t)
        }
        select(selected)
    }

    fun select(i: Int) {
        selected = i
        items.forEachIndexed { k, t ->
            val on = k == i
            t.isSelected = on
            t.background = if (on) context.rounded(Wired.alpha(accent, 0x29), 11f, Wired.alpha(accent, 0x66)) else null
            t.setTextColor(if (on) 0xFFE6F1FF.toInt() else Wired.CHIP_TEXT)
            t.typeface = if (on) Fonts.uiSemi else Fonts.ui
        }
    }
}

@SuppressLint("UseSwitchCompatOrMaterialCode")
fun Context.wiredSwitch(checked: Boolean, accent: Int, description: String, onChange: (Boolean) -> Unit): Switch {
    val s = Switch(this)
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    val thumb = GradientDrawable()
    thumb.shape = GradientDrawable.OVAL
    thumb.setSize(dpi(20), dpi(20))
    thumb.color = ColorStateList(states, intArrayOf(Wired.VOID, Wired.HINT))
    val track = GradientDrawable()
    track.cornerRadius = dp(13f)
    track.setSize(dpi(44), dpi(26))
    track.color = ColorStateList(states, intArrayOf(accent, Wired.TOGGLE_OFF))
    s.thumbDrawable = InsetDrawable(thumb, dpi(3))
    s.trackDrawable = track
    s.showText = false
    s.text = null
    s.contentDescription = description
    s.isChecked = checked
    s.minimumHeight = dpi(44)
    s.setOnCheckedChangeListener { _, v -> onChange(v) }
    return s
}

fun Context.wiredSlider(max: Int, value: Int, accent: Int, onChange: (Int) -> Unit): SeekBar {
    val sb = SeekBar(this)
    sb.max = max
    sb.progress = value
    sb.progressTintList = ColorStateList.valueOf(accent)
    sb.thumbTintList = ColorStateList.valueOf(accent)
    sb.progressBackgroundTintList = ColorStateList.valueOf(0xFF1A2436.toInt())
    sb.minimumHeight = dpi(32)
    sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) {}
    })
    return sb
}

/** Slight shrink while pressed: the "soft highlight" of dock and grid icons. */
@SuppressLint("ClickableViewAccessibility")
fun View.pressScale() {
    setOnTouchListener { v, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100L).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
        }
        false
    }
}

// ---------------------------------------------------------------- window

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
