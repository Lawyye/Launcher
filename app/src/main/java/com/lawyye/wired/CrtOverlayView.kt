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
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Top-most layer: CRT scanlines, vignette and short bursts of coloured glitch blocks.
 * Not clickable, so every touch passes through to the views below.
 */
class CrtOverlayView(ctx: Context) : View(ctx) {

    private class Block(val r: RectF, val color: Int)

    private var scanPaint: Paint? = null
    private val vignettePaint = Paint()
    private val blockPaint = Paint()
    private val blocks = ArrayList<Block>()
    private var accent = Palette.TEXT
    private var scanlines = true

    private val clearBlocks = Runnable {
        blocks.clear()
        invalidate()
    }

    private val flicker = Runnable {
        regenerate(false)
        invalidate()
        postDelayed(clearBlocks, 70L)
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun configure(scan: Boolean, accent: Int) {
        scanlines = scan
        this.accent = accent
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val line = max(3, (resources.displayMetrics.density * 1.3f).roundToInt())
        val bmp = Bitmap.createBitmap(1, line, Bitmap.Config.ARGB_8888)
        for (y in 0 until line) bmp.setPixel(0, y, if (y == line - 1) 0x5A000000 else 0)
        val p = Paint()
        p.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        scanPaint = p
        if (w > 0 && h > 0) {
            vignettePaint.shader = RadialGradient(
                w / 2f, h / 2f, max(w, h) * 0.72f,
                intArrayOf(0, 0, 0xA0000000.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (scanlines) scanPaint?.let { canvas.drawRect(0f, 0f, w, h, it) }
        canvas.drawRect(0f, 0f, w, h, vignettePaint)
        for (b in blocks) {
            blockPaint.color = b.color
            canvas.drawRect(b.r, blockPaint)
        }
    }

    fun burst(strong: Boolean) {
        regenerate(strong)
        invalidate()
        removeCallbacks(clearBlocks)
        removeCallbacks(flicker)
        postDelayed(clearBlocks, if (strong) 150L else 90L)
        postDelayed(flicker, if (strong) 230L else 170L)
    }

    private fun regenerate(strong: Boolean) {
        blocks.clear()
        if (width == 0 || height == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val d = resources.displayMetrics.density
        val colors = intArrayOf(accent, Palette.RED, Palette.CYAN, Color.WHITE, Palette.MAGENTA, Palette.ULTRA)

        // a torn horizontal band
        val bandY = Random.nextFloat() * h
        val bandH = d * (2f + Random.nextFloat() * if (strong) 40f else 14f)
        blocks.add(Block(RectF(0f, bandY, w, bandY + bandH), Palette.withAlpha(Color.WHITE, 26)))

        // a cluster of data blocks
        val n = if (strong) Random.nextInt(14, 30) else Random.nextInt(4, 11)
        val cx = Random.nextFloat() * w
        val cy = Random.nextFloat() * h
        repeat(n) {
            val bw = d * (4f + Random.nextFloat() * if (strong) 90f else 46f)
            val bh = d * (2f + Random.nextFloat() * 14f)
            val x = (cx + (Random.nextFloat() - 0.5f) * w * 0.7f).coerceIn(0f, max(0f, w - bw))
            val y = (cy + (Random.nextFloat() - 0.5f) * h * 0.3f).coerceIn(0f, max(0f, h - bh))
            val c = colors[Random.nextInt(colors.size)]
            blocks.add(Block(RectF(x, y, x + bw, y + bh), Palette.withAlpha(c, Random.nextInt(70, 190))))
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(clearBlocks)
        removeCallbacks(flicker)
        super.onDetachedFromWindow()
    }
}
