package com.lawyye.wired

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import kotlin.random.Random

/** Big clock text with permanent RGB split and occasional sliced glitch bursts. */
class GlitchTextView(ctx: Context) : View(ctx) {

    private val main = Paint(Paint.ANTI_ALIAS_FLAG)
    private val small = Paint(Paint.ANTI_ALIAS_FLAG)
    private val baseSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 84f, ctx.resources.displayMetrics)
    private var text = "--:--"
    private var suffix = ""
    private var glitchUntil = 0L
    private var strong = false
    private var lastShuffle = 0L
    private val shifts = FloatArray(8)

    var color: Int = Palette.BRIGHT

    init {
        main.typeface = Palette.monoBold
        small.typeface = Palette.mono
        main.textSize = baseSize
        small.textSize = baseSize * 0.3f
    }

    fun setText(t: String, s: String) {
        val relayout = t.length != text.length || s.length != suffix.length
        text = t
        suffix = s
        contentDescription = t
        if (relayout) requestLayout()
        invalidate()
    }

    fun burst(strong: Boolean) {
        this.strong = strong
        glitchUntil = SystemClock.uptimeMillis() + if (strong) 480L else 240L
        lastShuffle = 0L
        invalidate()
    }

    private fun gap(): Float = main.textSize * 0.08f

    private fun contentWidth(): Float =
        main.measureText(text) + if (suffix.isEmpty()) 0f else gap() + small.measureText(suffix)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        main.textSize = baseSize
        small.textSize = baseSize * 0.3f
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val avail = (size - paddingLeft - paddingRight).toFloat()
        val cw = contentWidth()
        if (mode != MeasureSpec.UNSPECIFIED && avail > 0f && cw > avail * 0.94f) {
            val f = avail * 0.94f / cw
            main.textSize = baseSize * f
            small.textSize = baseSize * 0.3f * f
        }
        val fm = main.fontMetrics
        val h = (fm.descent - fm.ascent).toInt() + paddingTop + paddingBottom
        val w = if (mode == MeasureSpec.UNSPECIFIED) contentWidth().toInt() + paddingLeft + paddingRight else size
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val fm = main.fontMetrics
        val base = paddingTop - fm.ascent
        val x0 = (width - contentWidth()) / 2f
        val d = resources.displayMetrics.density
        val now = SystemClock.uptimeMillis()

        if (now >= glitchUntil) {
            val split = d * 1.3f
            layer(canvas, x0 - split, base, Palette.withAlpha(Palette.RED, 160))
            layer(canvas, x0 + split, base, Palette.withAlpha(Palette.CYAN, 140))
            layer(canvas, x0, base, color)
            return
        }

        if (now - lastShuffle > 50L) {
            lastShuffle = now
            val amp = d * if (strong) 46f else 20f
            for (i in shifts.indices) {
                shifts[i] = if (Random.nextFloat() < 0.45f) 0f else (Random.nextFloat() - 0.5f) * amp
            }
        }
        val split = d * if (strong) 7f else 4f
        val top = base + fm.ascent
        val sliceH = (fm.descent - fm.ascent) / shifts.size
        for (i in shifts.indices) {
            canvas.save()
            canvas.clipRect(0f, top + i * sliceH, width.toFloat(), top + (i + 1) * sliceH + 1f)
            val sx = x0 + shifts[i]
            layer(canvas, sx - split, base, Palette.withAlpha(Palette.RED, 190))
            layer(canvas, sx + split, base, Palette.withAlpha(Palette.CYAN, 170))
            layer(canvas, sx, base, color)
            canvas.restore()
        }
        postInvalidateOnAnimation()
    }

    private fun layer(c: Canvas, x: Float, base: Float, col: Int) {
        main.color = col
        c.drawText(text, x, base, main)
        if (suffix.isNotEmpty()) {
            small.color = col
            c.drawText(suffix, x + main.measureText(text) + gap(), base, small)
        }
    }
}
