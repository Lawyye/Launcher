package com.lawyye.wired

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorFilter
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/** Colour treatment of app icons: original (default), tinted with the accent, or monochrome. */
fun iconFilter(mode: Int, accent: Int): ColorFilter? = when (mode) {
    Prefs.ICON_TINT -> Wired.duotone(0xFF12213B.toInt(), accent)
    Prefs.ICON_MONO -> Wired.duotone(0xFF1A1F2A.toInt(), 0xFFC9D3E3.toInt())
    else -> null
}

/** A dark rounded card with optional header, message and rows; used for every menu. */
class WiredDialog(private val act: Activity, private val accent: Int) {

    private val dialog = Dialog(act)
    private val card = LinearLayout(act)
    private var rows = 0

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        card.orientation = LinearLayout.VERTICAL
        card.background = act.rounded(0xF50C1424.toInt(), 20f, 0x2E82B9FF)
        card.clipToOutline = true
        card.setPadding(0, act.dpi(6), 0, act.dpi(6))
    }

    fun header(icon: Bitmap?, title: String, meta: String?, iconFilter: ColorFilter? = null): WiredDialog {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(act.dpi(16), act.dpi(10), act.dpi(16), act.dpi(14))
        if (icon != null) {
            val iv = ImageView(act)
            iv.setImageBitmap(icon)
            iv.colorFilter = iconFilter
            iv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            row.addView(iv, LinearLayout.LayoutParams(act.dpi(44), act.dpi(44)))
        }
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val t = act.label(16f, Wired.CLOCK, Fonts.uiSemi)
        t.text = title
        t.singleLine()
        col.addView(t)
        if (!meta.isNullOrEmpty()) {
            val m = act.meta(meta)
            m.singleLine()
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.topMargin = act.dpi(4)
            col.addView(m, lp)
        }
        val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
        if (icon != null) lp.leftMargin = act.dpi(12)
        row.addView(col, lp)
        card.addView(row)
        val line = View(act)
        line.setBackgroundColor(Wired.LINE_SOFT)
        card.addView(line, LinearLayout.LayoutParams(MATCH, act.dpi(1)))
        return this
    }

    fun message(text: String): WiredDialog {
        val tv = act.label(14f, Wired.TEXT_SOFT, Fonts.ui)
        tv.text = text
        tv.setLineSpacing(0f, 1.4f)
        tv.setPadding(act.dpi(18), act.dpi(12), act.dpi(18), act.dpi(10))
        card.addView(tv)
        return this
    }

    fun option(
        iconRes: Int?,
        label: String,
        danger: Boolean = false,
        checked: Boolean = false,
        action: () -> Unit,
    ): WiredDialog {
        val color = if (danger) Wired.RED_TEXT else Wired.TEXT
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = act.dpi(50)
        row.setPadding(act.dpi(16), 0, act.dpi(16), 0)
        row.background = act.ripple(null, 0f, accent)
        row.isClickable = true
        row.isFocusable = true
        if (iconRes != null) {
            val iv = act.iconView(iconRes, color, 19)
            row.addView(iv)
        }
        val t = act.label(15f, color, Fonts.ui)
        t.text = label
        val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
        if (iconRes != null) lp.leftMargin = act.dpi(12)
        row.addView(t, lp)
        if (checked) row.addView(act.iconView(R.drawable.ic_check, accent, 18))
        row.setOnClickListener {
            dialog.dismiss()
            action()
        }
        card.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        rows++
        return this
    }

    fun show() {
        val scroll = ScrollView(act)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(card, ViewGroup.LayoutParams(MATCH, WRAP))
        dialog.setContentView(scroll)
        val w = dialog.window ?: return
        w.setBackgroundDrawable(ColorDrawable(0))
        w.setDimAmount(0.4f)
        val dm = act.resources.displayMetrics
        w.setLayout(min(dm.widthPixels - act.dpi(48), act.dpi(320)), ViewGroup.LayoutParams.WRAP_CONTENT)
        w.setWindowAnimations(android.R.style.Animation_Dialog)
        try {
            dialog.show()
        } catch (t: Throwable) {
            // activity is finishing
        }
    }
}

/** Glass bar with up to five favourite apps, original icons by default. */
class DockView(ctx: Context, private val host: Host) : LinearLayout(ctx) {

    interface Host {
        fun launchApp(app: AppEntry, from: View)
        fun appMenu(app: AppEntry)
        fun openDrawer()
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        background = ctx.rounded(0x85080D17.toInt(), 30f, Wired.LINE)
        setPadding(ctx.dpi(12), 0, ctx.dpi(12), 0)
    }

    fun bind(apps: List<AppEntry>, iconDp: Int, iconMode: Int, labels: Boolean, accent: Int) {
        removeAllViews()
        val ctx = context
        if (apps.isEmpty()) {
            val t = ctx.meta("док пуст · удерживай приложение в списке → «В док»", 11f)
            t.gravity = Gravity.CENTER
            t.setPadding(ctx.dpi(8), 0, ctx.dpi(8), 0)
            t.setOnClickListener { host.openDrawer() }
            addView(t, LayoutParams(MATCH, MATCH))
            return
        }
        val px = ctx.dpi(iconDp)
        val filter = iconFilter(iconMode, accent)
        for (app in apps) {
            val cell = LinearLayout(ctx)
            cell.orientation = VERTICAL
            cell.gravity = Gravity.CENTER
            cell.contentDescription = app.label
            cell.isClickable = true
            cell.isFocusable = true
            val iv = ImageView(ctx)
            iv.colorFilter = filter
            iv.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            IconCache.bind(iv, app, px)
            cell.addView(iv, LayoutParams(px, px))
            if (labels) {
                val t = ctx.label(11f, Wired.TEXT_SOFT, Fonts.ui)
                t.text = app.label
                t.singleLine()
                t.gravity = Gravity.CENTER
                val lp = LayoutParams(px + ctx.dpi(16), WRAP)
                lp.topMargin = ctx.dpi(5)
                cell.addView(t, lp)
            }
            cell.pressScale()
            cell.setOnClickListener { host.launchApp(app, iv) }
            cell.setOnLongClickListener {
                cell.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                host.appMenu(app)
                true
            }
            addView(cell, LayoutParams(0, MATCH, 1f))
        }
    }
}

/** Thin dashed thirds over the picture while composing. */
class GuidesView(ctx: Context) : View(ctx) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    init {
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = ctx.dp(1f)
        p.color = 0x29B7F5FF
        p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(ctx.dp(5f), ctx.dp(5f)), 0f)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..2) {
            canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, p)
            canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, p)
        }
    }
}

/** A titled card for settings, rows separated by hairlines. */
class Group(ctx: Context, title: String) : LinearLayout(ctx) {
    val body = LinearLayout(ctx)

    init {
        orientation = VERTICAL
        val t = ctx.meta(title)
        t.setPadding(ctx.dpi(4), ctx.dpi(28), 0, ctx.dpi(10))
        addView(t)
        body.orientation = VERTICAL
        body.background = ctx.rounded(Wired.SURFACE, 20f, 0x1A82B9FF)
        body.clipToOutline = true
        addView(body, LayoutParams(MATCH, WRAP))
    }

    fun add(v: View): Group {
        if (body.childCount > 0) {
            val line = View(context)
            line.setBackgroundColor(Wired.LINE_SOFT)
            body.addView(line, LayoutParams(MATCH, context.dpi(1)))
        }
        body.addView(v, LayoutParams(MATCH, WRAP))
        return this
    }
}

/** One settings row: icon tile, label, value or control on the right. */
fun Context.settingsRow(iconRes: Int, title: String, accent: Int, trailing: View? = null, value: String? = null, onClick: (() -> Unit)? = null): LinearLayout {
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.minimumHeight = dpi(56)
    row.setPadding(dpi(16), dpi(6), dpi(if (trailing != null) 10 else 16), dpi(6))
    val tile = FrameLayout(this)
    tile.background = rounded(Wired.alpha(accent, 0x1A), 10f)
    val iv = ImageView(this)
    iv.setImageDrawable(icon(iconRes, accent))
    iv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    tile.addView(iv, FrameLayout.LayoutParams(dpi(18), dpi(18), Gravity.CENTER))
    row.addView(tile, LinearLayout.LayoutParams(dpi(32), dpi(32)))
    val t = label(15f, Wired.TEXT, Fonts.ui)
    t.text = title
    val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
    lp.leftMargin = dpi(12)
    row.addView(t, lp)
    if (value != null) {
        val v = label(13f, Wired.TEXT2, Fonts.ui)
        v.text = value
        v.singleLine()
        v.tag = "value"
        row.addView(v)
    }
    if (trailing != null) row.addView(trailing)
    if (onClick != null) {
        val ch = iconView(R.drawable.ic_chevron, Wired.CHEVRON, 16)
        (ch.layoutParams as LinearLayout.LayoutParams).leftMargin = dpi(8)
        row.addView(ch)
        row.background = ripple(null, 0f, accent)
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener { onClick() }
    }
    return row
}

fun LinearLayout.setRowValue(text: String) {
    for (i in 0 until childCount) {
        val c = getChildAt(i)
        if (c is TextView && c.tag == "value") c.text = text
    }
}
