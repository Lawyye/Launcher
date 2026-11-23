package com.lawyye.wired

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * "CRT Window": a small old monitor on the home screen showing one of the user's own pictures,
 * colour filtered, behind scanlines and glass. It switches on like a tube (a bright line that
 * opens into the picture) after unlock or a tap. A slow rolling band and flicker run only in
 * the Visual profile, at ~30 fps, and only while the home screen is visible.
 */
class CrtWindowView(ctx: Context) : View(ctx) {

    var image: Bitmap? = null
        set(v) {
            field = v
            invalidate()
        }
    var filter = ImageFilter.WIRED
        set(v) {
            field = v
            imagePaint.colorFilter = v.colorFilter()
            invalidate()
        }
    var accent = Wired.BLUE
        set(v) {
            field = v
            ledPaint.color = v
            ledPaint.setShadowLayer(context.dp(5f), 0f, 0f, v)
            invalidate()
        }
    var caption = "crt_01 · node 07"
    var emptyText = "добавь картинки: настройки → CRT-окно"

    /** Rolling band and flicker (Visual profile only). */
    var continuous = false
        set(v) {
            field = v
            updateLoop()
        }

    private val d = ctx.resources.displayMetrics.density
    private val bezel = RectF()
    private val screen = RectF()
    private val screenPath = Path()
    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bezelStroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val screenBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val linesPaint = Paint()
    private val noisePaint = Paint()
    private val vignette = Paint()
    private val glare = Paint()
    private val band = Paint()
    private val flash = Paint()
    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobStroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()

    /** 0 = off, 1 = fully on. */
    private var power = 1f
    private var powerAnim: ValueAnimator? = null
    private var looping = false
    private var bandStart = SystemClock.uptimeMillis()
    private var flickerAlpha = 1f

    private val loop = object : Runnable {
        override fun run() {
            if (!looping) return
            flickerAlpha = if (Random.nextFloat() < 0.04f) 0.84f else 1f
            invalidate()
            postDelayed(this, 33L)
        }
    }

    init {
        contentDescription = "CRT-окно"
        isClickable = true
        isFocusable = true
        bezelStroke.style = Paint.Style.STROKE
        bezelStroke.strokeWidth = d
        bezelStroke.color = 0x2E82B9FF
        screenBg.color = 0xFF02050B.toInt()
        imagePaint.colorFilter = filter.colorFilter()
        linesPaint.shader = BitmapShader(
            EffectTiles.lines((3 * d).roundToInt(), d.roundToInt(), 0x4D000000),
            Shader.TileMode.REPEAT, Shader.TileMode.REPEAT,
        )
        noisePaint.shader = BitmapShader(EffectTiles.grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        noisePaint.alpha = 26
        knobPaint.color = 0xFF0B1220.toInt()
        knobStroke.style = Paint.Style.STROKE
        knobStroke.strokeWidth = d
        knobStroke.color = 0x3882B9FF
        textPaint.typeface = Fonts.mono
        textPaint.textSize = 10f * ctx.resources.displayMetrics.scaledDensity
        textPaint.color = Wired.TEXT2
        flash.color = 0xFFE6F1FF.toInt()
        accent = Wired.BLUE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bezel.set(d * 1f, d * 1f, w - d * 1f, h - d * 1f)
        val pad = d * 14f
        screen.set(bezel.left + pad, bezel.top + pad, bezel.right - pad, bezel.bottom - d * 50f)
        screenPath.reset()
        screenPath.addRoundRect(screen, d * 20f, d * 20f, Path.Direction.CW)
        bezelPaint.shader = LinearGradient(
            0f, 0f, w * 0.4f, h.toFloat(),
            intArrayOf(0xFF141F33.toInt(), 0xFF0A111E.toInt(), 0xFF070C16.toInt()),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
        vignette.shader = RadialGradient(
            screen.centerX(), screen.centerY(), max(screen.width(), screen.height()) * 0.62f,
            intArrayOf(0, 0, 0xB8000000.toInt()), floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP,
        )
        glare.shader = RadialGradient(
            screen.left + screen.width() * 0.28f, screen.top + screen.height() * 0.18f, screen.width() * 0.45f,
            intArrayOf(0x17FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP,
        )
        band.shader = LinearGradient(
            0f, 0f, 0f, d * 70f,
            intArrayOf(0x00B7F5FF, 0x12B7F5FF, 0x00B7F5FF), null, Shader.TileMode.CLAMP,
        )
    }

    /** The tube switching on: a bright line that opens into the picture (~700 ms). */
    fun powerOn(animate: Boolean) {
        powerAnim?.cancel()
        if (!animate) {
            power = 1f
            invalidate()
            return
        }
        power = 0f
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 720L
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            power = it.animatedValue as Float
            invalidate()
        }
        powerAnim = a
        a.start()
    }

    fun onVisible(visible: Boolean) {
        looping = visible && continuous
        updateLoop()
        if (!visible) {
            powerAnim?.end()
        }
    }

    private fun updateLoop() {
        removeCallbacks(loop)
        if (looping && continuous && isAttachedToWindow) post(loop)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateLoop()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(loop)
        powerAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        if (width == 0 || height == 0) return
        // bezel
        val r = d * 28f
        c.drawRoundRect(bezel, r, r, bezelPaint)
        c.drawRoundRect(bezel, r, r, bezelStroke)

        // screen
        c.save()
        c.clipPath(screenPath)
        c.drawRect(screen, screenBg)
        if (power > 0f) drawTube(c)
        c.drawRect(screen, vignette)
        c.drawRect(screen, glare)
        c.restore()

        // bottom bar: caption, two knobs, power LED
        val cy = (screen.bottom + bezel.bottom) / 2f
        c.drawText(caption, screen.left + d * 6f, cy + textPaint.textSize * 0.35f, textPaint)
        val ledX = screen.right - d * 8f
        ledPaint.alpha = if (power < 0.04f) 90 else 255
        c.drawCircle(ledX, cy, d * 3.5f, ledPaint)
        for (k in 1..2) {
            val kx = ledX - d * (10f + 22f * k)
            c.drawCircle(kx, cy, d * 6f, knobPaint)
            c.drawCircle(kx, cy, d * 6f, knobStroke)
        }
    }

    private fun drawTube(c: Canvas) {
        // phase A (0..0.2): a line grows sideways; B (0.2..0.55): it opens vertically; C: settles
        val p = power
        val cx = screen.centerX()
        val cyy = screen.centerY()
        val hw: Float
        val hh: Float
        var white: Float
        when {
            p < 0.2f -> {
                hw = screen.width() / 2f * (p / 0.2f)
                hh = max(d, screen.height() * 0.006f)
                white = 1f
            }
            p < 0.55f -> {
                val t = (p - 0.2f) / 0.35f
                hw = screen.width() / 2f
                hh = screen.height() / 2f * (0.012f + 0.988f * t)
                white = 0.85f - 0.7f * t
            }
            else -> {
                hw = screen.width() / 2f
                hh = screen.height() / 2f
                white = 0.15f * (1f - (p - 0.55f) / 0.45f)
            }
        }
        if (p >= 1f) white = 0f
        c.save()
        c.clipRect(cx - hw, cyy - hh, cx + hw, cyy + hh)
        val bmp = image
        if (bmp != null && !bmp.isRecycled) {
            val s = max(screen.width() / bmp.width, screen.height() / bmp.height)
            matrix.reset()
            matrix.postScale(s, s)
            matrix.postTranslate(cx - bmp.width * s / 2f, cyy - bmp.height * s / 2f)
            imagePaint.alpha = (255 * flickerAlpha).toInt()
            c.drawBitmap(bmp, matrix, imagePaint)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = 0xB3D9E9FF.toInt()
            c.drawText(emptyText, cx, cyy, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = Wired.TEXT2
        }
        c.drawRect(screen, noisePaint)
        c.drawRect(screen, linesPaint)
        if (continuous && looping) {
            val period = 9000f
            val t = ((SystemClock.uptimeMillis() - bandStart) % period.toLong()) / period
            val y = screen.top - d * 70f + t * (screen.height() + d * 140f)
            c.save()
            c.translate(0f, y)
            c.drawRect(screen.left, 0f, screen.right, d * 70f, band)
            c.restore()
        }
        if (white > 0f) {
            flash.alpha = (white * 255).toInt().coerceIn(0, 255)
            c.drawRect(screen, flash)
        }
        c.restore()
    }
}
