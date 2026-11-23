package com.lawyye.wired

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/** Small tiles shared by every effect layer: built once, repeated by the GPU. */
object EffectTiles {
    private var grainTile: Bitmap? = null

    fun grain(): Bitmap {
        grainTile?.let { return it }
        val n = 160
        val px = IntArray(n * n)
        val rnd = Random(7)
        for (i in px.indices) {
            val v = rnd.nextInt(256)
            px[i] = Color.argb(255, v, v, v)
        }
        val b = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        grainTile = b
        return b
    }

    /** Horizontal lines: [line] px of [color] every [period] px. */
    fun lines(period: Int, line: Int, color: Int): Bitmap {
        val p = period.coerceAtLeast(2)
        val b = Bitmap.createBitmap(1, p, Bitmap.Config.ARGB_8888)
        for (y in 0 until p) b.setPixel(0, y, if (y >= p - line.coerceIn(1, p - 1)) color else 0)
        return b
    }
}

/**
 * Top-most layer of the home screen: very thin scanlines, grain and a vignette, drawn from
 * repeated tiles (no per-frame work), plus a short glitch that only happens on an event.
 * Never clickable, so every touch passes through.
 */
class EffectsOverlay(ctx: Context) : View(ctx) {

    private class Block(val r: RectF, val color: Int)

    var scan = true
    var grain = true
    var fxOn = true
        set(v) {
            field = v
            visibility = if (v) VISIBLE else GONE
        }
    var accent = Wired.BLUE

    private val scanPaint = Paint()
    private val grainPaint = Paint()
    private val vignettePaint = Paint()
    private val blockPaint = Paint()
    private val blocks = ArrayList<Block>()
    private val clear = Runnable {
        blocks.clear()
        invalidate()
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val d = ctx.resources.displayMetrics.density
        scanPaint.shader = BitmapShader(
            EffectTiles.lines((3 * d).roundToInt(), d.roundToInt(), 0x09B7F5FF),
            Shader.TileMode.REPEAT, Shader.TileMode.REPEAT,
        )
        grainPaint.shader = BitmapShader(EffectTiles.grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        grainPaint.alpha = 15
    }

    fun configure(enabled: Boolean, scan: Boolean, grain: Boolean, accent: Int) {
        this.fxOn = enabled
        this.scan = scan
        this.grain = grain
        this.accent = accent
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            vignettePaint.shader = RadialGradient(
                w / 2f, h * 0.45f, max(w, h) * 0.75f,
                intArrayOf(0, 0, 0x80000000.toInt()), floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!fxOn) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (scan) canvas.drawRect(0f, 0f, w, h, scanPaint)
        if (grain) canvas.drawRect(0f, 0f, w, h, grainPaint)
        canvas.drawRect(0f, 0f, w, h, vignettePaint)
        for (b in blocks) {
            blockPaint.color = b.color
            canvas.drawRect(b.r, blockPaint)
        }
    }

    /** A short tear: one bright band and a few colour blocks, gone after ~180 ms. */
    fun burst(strong: Boolean) {
        if (!fxOn || width == 0 || height == 0) return
        blocks.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        val d = resources.displayMetrics.density
        val colors = intArrayOf(accent, Wired.RED, Wired.CYAN, Color.WHITE)
        val bandY = Random.nextFloat() * h
        val bandH = d * (2f + Random.nextFloat() * if (strong) 26f else 10f)
        blocks.add(Block(RectF(0f, bandY, w, bandY + bandH), 0x1FFFFFFF))
        val n = if (strong) Random.nextInt(8, 16) else Random.nextInt(3, 7)
        val cy = Random.nextFloat() * h
        repeat(n) {
            val bw = d * (6f + Random.nextFloat() * if (strong) 70f else 36f)
            val bh = d * (2f + Random.nextFloat() * 8f)
            val x = (Random.nextFloat() * w).coerceAtMost(max(0f, w - bw))
            val y = (cy + (Random.nextFloat() - 0.5f) * h * 0.25f).coerceIn(0f, max(0f, h - bh))
            blocks.add(Block(RectF(x, y, x + bw, y + bh), Wired.alpha(colors[Random.nextInt(colors.size)], Random.nextInt(60, 150))))
        }
        invalidate()
        removeCallbacks(clear)
        postDelayed(clear, if (strong) 260L else 180L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(clear)
        super.onDetachedFromWindow()
    }
}

/**
 * The big thin clock. Stacked ("17" over "01") next to a picture, or one line. Soft glow,
 * optional 1.5 dp chromatic aberration, and a sliced glitch only when [burst] is called.
 */
class ClockView(ctx: Context) : View(ctx) {

    var stacked = true
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }
    var alignEnd = false
        set(v) {
            field = v
            invalidate()
        }
    var aberration = false
        set(v) {
            field = v
            invalidate()
        }
    var accent = Wired.BLUE
        set(v) {
            field = v
            applyGlow()
            invalidate()
        }
    var maxSizeSp = 128f
        set(v) {
            field = v
            requestLayout()
        }

    private var hh = "00"
    private var mm = "00"
    private var suffix = ""
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val ghost = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val small = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glitchUntil = 0L
    private var strong = false
    private var lastShuffle = 0L
    private val shifts = FloatArray(6)

    init {
        paint.typeface = Fonts.clock
        paint.color = Wired.CLOCK
        paint.letterSpacing = -0.05f
        ghost.typeface = Fonts.clock
        ghost.letterSpacing = -0.05f
        small.typeface = Fonts.clock
        small.color = Wired.CLOCK
        applyGlow()
    }

    private fun applyGlow() {
        paint.setShadowLayer(context.dp(14f), 0f, 0f, Wired.alpha(accent, 0x4D))
    }

    fun setTime(h: String, m: String, sec: String) {
        val relayout = h.length != hh.length || m.length != mm.length || sec.length != suffix.length
        hh = h
        mm = m
        suffix = sec
        contentDescription = "$h:$m"
        if (relayout) requestLayout()
        invalidate()
    }

    fun burst(strong: Boolean) {
        this.strong = strong
        glitchUntil = SystemClock.uptimeMillis() + if (strong) 420L else 220L
        lastShuffle = 0L
        invalidate()
    }

    private fun lines(): List<String> = if (stacked) listOf(hh, mm) else listOf("$hh:$mm")

    private fun lineHeight(): Float = paint.textSize * if (stacked) 0.9f else 1.0f

    private fun suffixWidth(): Float = if (suffix.isEmpty() || stacked) 0f else small.measureText(suffix) + paint.textSize * 0.06f

    private fun contentWidth(): Float = (lines().maxOfOrNull { paint.measureText(it) } ?: 0f) + suffixWidth()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val base = maxSizeSp * resources.displayMetrics.scaledDensity
        paint.textSize = base
        small.textSize = base * 0.28f
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val avail = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).toFloat()
        val cw = contentWidth()
        if (mode != MeasureSpec.UNSPECIFIED && avail > 0f && cw > avail) {
            paint.textSize = base * avail / cw
            small.textSize = paint.textSize * 0.28f
        }
        ghost.textSize = paint.textSize
        val w = contentWidth().toInt() + paddingLeft + paddingRight + 2
        val h = (lineHeight() * lines().size).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val fm = paint.fontMetrics
        val a = -fm.ascent
        val d = fm.descent
        val lh = lineHeight()
        val now = SystemClock.uptimeMillis()
        val glitching = now < glitchUntil
        val dpx = resources.displayMetrics.density
        if (glitching && now - lastShuffle > 50L) {
            lastShuffle = now
            val amp = dpx * if (strong) 34f else 14f
            for (i in shifts.indices) shifts[i] = if (Random.nextFloat() < 0.45f) 0f else (Random.nextFloat() - 0.5f) * amp
        }
        lines().forEachIndexed { i, text ->
            val top = paddingTop + i * lh
            val base = top + (lh - (a + d)) / 2f + a
            val tw = paint.measureText(text)
            val x = if (alignEnd) width - paddingRight - tw - suffixWidth() else paddingLeft.toFloat()
            if (!glitching) {
                if (aberration) {
                    ghost.color = 0x38FF4565
                    canvas.drawText(text, x - dpx * 1.5f, base, ghost)
                    ghost.color = 0x38B7F5FF
                    canvas.drawText(text, x + dpx * 1.5f, base, ghost)
                }
                canvas.drawText(text, x, base, paint)
            } else {
                val split = dpx * if (strong) 6f else 3f
                val slice = lh / shifts.size
                for (k in shifts.indices) {
                    canvas.save()
                    canvas.clipRect(0f, top + k * slice, width.toFloat(), top + (k + 1) * slice + 1f)
                    val sx = x + shifts[k]
                    ghost.color = 0xB3FF4565.toInt()
                    canvas.drawText(text, sx - split, base, ghost)
                    ghost.color = 0xA6B7F5FF.toInt()
                    canvas.drawText(text, sx + split, base, ghost)
                    canvas.drawText(text, sx, base, paint)
                    canvas.restore()
                }
            }
            if (!stacked && suffix.isNotEmpty()) {
                canvas.drawText(suffix, x + tw + paint.textSize * 0.06f, base, small)
            }
        }
        if (glitching) postInvalidateOnAnimation()
    }
}
