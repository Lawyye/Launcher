package com.lawyye.wired

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Flat fill with a light top-left edge and a dark bottom-right edge: the old 9x window look. */
class BevelDrawable(
    private val fill: Int,
    private val light: Int,
    private val dark: Int,
    private val edge: Float,
) : Drawable() {

    private val p = Paint()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val l = b.left.toFloat()
        val t = b.top.toFloat()
        val r = b.right.toFloat()
        val btm = b.bottom.toFloat()
        p.color = fill
        canvas.drawRect(l, t, r, btm, p)
        p.color = light
        canvas.drawRect(l, t, r, t + edge, p)
        canvas.drawRect(l, t, l + edge, btm, p)
        p.color = dark
        canvas.drawRect(l, btm - edge, r, btm, p)
        canvas.drawRect(r - edge, t, r, btm, p)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/** Retro dark "window" dialog with a title bar, used for every menu in the launcher. */
class WinDialog(private val ctx: Context, title: String, private val accent: Int) {

    private val dialog = Dialog(ctx)
    private val body = LinearLayout(ctx)

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val frame = LinearLayout(ctx)
        frame.orientation = LinearLayout.VERTICAL
        frame.background = BevelDrawable(0xFF0A0E15.toInt(), 0xFF7E8DA6.toInt(), 0xFF000000.toInt(), ctx.dp(2f))
        val p = ctx.dpi(3)
        frame.setPadding(p, p, p, p)

        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Palette.darker(accent, 0.30f), Palette.darker(accent, 0.75f)),
        )
        bar.setPadding(ctx.dpi(8), ctx.dpi(4), ctx.dpi(4), ctx.dpi(4))

        val t = ctx.monoText(12f, Color.WHITE, bold = true)
        t.text = title
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        bar.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))

        for (glyph in arrayOf("_", "□", "×")) {
            val b = winButton(glyph, 11f)
            b.setPadding(ctx.dpi(7), ctx.dpi(1), ctx.dpi(7), ctx.dpi(2))
            if (glyph == "×") b.setOnClickListener { dialog.dismiss() }
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.leftMargin = ctx.dpi(3)
            bar.addView(b, lp)
        }
        frame.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        body.orientation = LinearLayout.VERTICAL
        body.setPadding(ctx.dpi(12), ctx.dpi(12), ctx.dpi(12), ctx.dpi(10))
        frame.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))

        dialog.setContentView(frame)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun winButton(label: String, size: Float): TextView {
        val b = ctx.monoText(size, Color.WHITE, bold = true)
        b.text = label
        b.gravity = Gravity.CENTER
        b.background = BevelDrawable(0xFF1B2433.toInt(), 0xFF9AA8C0.toInt(), 0xFF000000.toInt(), ctx.dp(1.5f))
        return b
    }

    fun message(msg: String): WinDialog {
        val tv = ctx.monoText(12f, Palette.TEXT)
        tv.text = msg
        tv.setLineSpacing(0f, 1.3f)
        tv.setPadding(ctx.dpi(2), 0, 0, ctx.dpi(10))
        body.addView(tv, LinearLayout.LayoutParams(MATCH, WRAP))
        return this
    }

    fun option(label: String, action: () -> Unit): WinDialog {
        val row = ctx.monoText(14f, Palette.TEXT)
        row.text = "▸ $label"
        row.setPadding(ctx.dpi(6), ctx.dpi(11), ctx.dpi(6), ctx.dpi(11))
        row.background = pressBg(accent)
        row.setOnClickListener {
            dialog.dismiss()
            action()
        }
        body.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        return this
    }

    fun input(initial: String, hintText: String): EditText {
        val e = EditText(ctx)
        e.setText(initial)
        e.hint = hintText
        e.typeface = Palette.mono
        e.textSize = 15f
        e.setTextColor(Palette.BRIGHT)
        e.setHintTextColor(Palette.DIM)
        e.setSingleLine(true)
        e.background = BevelDrawable(0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF7E8DA6.toInt(), ctx.dp(1.5f))
        e.setPadding(ctx.dpi(10), ctx.dpi(10), ctx.dpi(10), ctx.dpi(10))
        e.setSelection(initial.length)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.bottomMargin = ctx.dpi(12)
        body.addView(e, lp)
        return e
    }

    fun buttons(negative: String, positive: String, onPositive: () -> Unit): WinDialog {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.END
        val neg = winButton(negative, 12f)
        neg.setPadding(ctx.dpi(16), ctx.dpi(8), ctx.dpi(16), ctx.dpi(8))
        neg.setOnClickListener { dialog.dismiss() }
        val pos = winButton(positive, 12f)
        pos.setPadding(ctx.dpi(16), ctx.dpi(8), ctx.dpi(16), ctx.dpi(8))
        pos.setOnClickListener {
            dialog.dismiss()
            onPositive()
        }
        row.addView(neg)
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.leftMargin = ctx.dpi(8)
        row.addView(pos, lp)
        body.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        return this
    }

    fun show(): WinDialog {
        try {
            dialog.show()
            val w = (ctx.resources.displayMetrics.widthPixels * 0.86f).toInt()
            dialog.window?.setLayout(w, WRAP)
        } catch (e: Throwable) {
            // activity went away; nothing to show
        }
        return this
    }
}
