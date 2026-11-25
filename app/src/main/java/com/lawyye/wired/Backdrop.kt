package com.lawyye.wired

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.max

/**
 * Built-in wallpaper "Провода": a night sky with power lines over a sleeping city, drawn with
 * vector paths in a 390x844 design space and centre-cropped to any screen.
 */
class NightScene {
    private val w0 = 390f
    private val h0 = 844f

    private val sky = Paint()
    private val haze = Paint()
    private val moonGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wire = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wires = Path()
    private val city = Path()
    private val poles = listOf(
        RectF(66f, 258f, 74f, 858f), RectF(36f, 295f, 104f, 299f), RectF(40f, 330f, 100f, 333.5f),
        RectF(327f, 392f, 332.5f, 852f), RectF(304f, 405f, 356f, 408f),
        RectF(203f, 524f, 206f, 854f), RectF(191f, 529f, 219f, 531f),
    )
    private val stars = floatArrayOf(
        38f, 118f, 0.9f, 0.5f, 122f, 72f, 0.7f, 0.4f, 214f, 96f, 1f, 0.55f, 352f, 88f, 0.8f, 0.45f,
        250f, 150f, 0.6f, 0.35f, 170f, 190f, 0.8f, 0.4f, 372f, 262f, 0.7f, 0.4f, 18f, 330f, 0.6f, 0.3f,
        142f, 292f, 0.7f, 0.35f, 236f, 338f, 0.9f, 0.4f,
    )
    private val windows = floatArrayOf(
        298f, 662f, 0.75f, 306f, 676f, 0.5f, 112f, 670f, 0.6f, 118f, 684f, 0.4f,
        244f, 688f, 0.55f, 358f, 678f, 0.65f, 168f, 694f, 0.45f, 52f, 696f, 0.5f,
    )

    init {
        sky.shader = LinearGradient(
            0f, 0f, 0f, h0,
            intArrayOf(0xFF02040A.toInt(), 0xFF071228.toInt(), 0xFF10264A.toInt(), 0xFF173259.toInt()),
            floatArrayOf(0f, 0.45f, 0.78f, 1f), Shader.TileMode.CLAMP,
        )
        haze.shader = RadialGradient(
            w0 * 0.62f, h0 * 0.76f, h0 * 0.5f,
            intArrayOf(0x574C7FC0, 0x004C7FC0), null, Shader.TileMode.CLAMP,
        )
        moonGlow.shader = RadialGradient(
            MOON_X, MOON_Y, 64f, intArrayOf(0x6BCFE3FF, 0x00CFE3FF), null, Shader.TileMode.CLAMP,
        )
        wire.style = Paint.Style.STROKE
        wire.strokeCap = Paint.Cap.ROUND
        wire.color = 0xFF010309.toInt()
        wire.strokeWidth = 1.1f

        fun q(x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float) {
            wires.moveTo(x0, y0)
            wires.quadTo(cx, cy, x1, y1)
        }
        q(-12f, 168f, 190f, 250f, 402f, 132f)
        q(-12f, 236f, 16f, 280f, 40f, 298f)
        q(-12f, 252f, 44f, 282f, 70f, 297f)
        q(-12f, 268f, 60f, 286f, 100f, 298f)
        q(40f, 298f, 180f, 400f, 307f, 407f)
        q(70f, 297f, 196f, 392f, 330f, 406f)
        q(100f, 298f, 214f, 384f, 353f, 407f)
        q(307f, 407f, 350f, 392f, 402f, 372f)
        q(330f, 406f, 366f, 396f, 402f, 384f)
        q(353f, 407f, 378f, 404f, 402f, 398f)
        q(44f, 332f, 120f, 470f, 193f, 531f)
        q(96f, 332f, 160f, 462f, 216f, 531f)
        q(216f, 531f, 300f, 600f, 402f, 610f)
        q(193f, 531f, 280f, 612f, 402f, 630f)

        val pts = floatArrayOf(
            0f, 700f, 0f, 860f, 390f, 860f, 390f, 690f, 372f, 690f, 372f, 668f, 350f, 668f, 350f, 700f,
            318f, 700f, 318f, 652f, 292f, 652f, 292f, 704f, 262f, 704f, 262f, 676f, 236f, 676f, 236f, 712f,
            190f, 712f, 190f, 684f, 160f, 684f, 160f, 706f, 128f, 706f, 128f, 660f, 104f, 660f, 104f, 702f,
            78f, 702f, 78f, 688f, 40f, 688f, 40f, 700f,
        )
        city.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) {
            city.lineTo(pts[i], pts[i + 1])
            i += 2
        }
        city.close()
    }

    fun draw(c: Canvas, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val s = max(w / w0, h / h0)
        c.save()
        c.translate((w - w0 * s) / 2f, (h - h0 * s) / 2f)
        c.scale(s, s)
        c.drawRect(-20f, -20f, w0 + 20f, h0 + 20f, sky)
        c.drawRect(-20f, -20f, w0 + 20f, h0 + 20f, haze)
        solid.color = 0xFFCFE3FF.toInt()
        var i = 0
        while (i < stars.size) {
            solid.alpha = (stars[i + 3] * 255).toInt()
            c.drawCircle(stars[i], stars[i + 1], stars[i + 2], solid)
            i += 4
        }
        c.drawCircle(MOON_X, MOON_Y, 64f, moonGlow)
        solid.color = 0xEBE4EEFF.toInt()
        c.drawCircle(MOON_X, MOON_Y, 8.5f, solid)
        c.drawPath(wires, wire)
        solid.color = 0xFF010309.toInt()
        for (r in poles) c.drawRect(r, solid)
        solid.color = 0xFF03060E.toInt()
        c.drawPath(city, solid)
        solid.color = 0xFF9CC7FF.toInt()
        i = 0
        while (i < windows.size) {
            solid.alpha = (windows[i + 2] * 255).toInt()
            c.drawRect(windows[i], windows[i + 1], windows[i] + 3f, windows[i + 1] + 4f, solid)
            i += 3
        }
        c.restore()
    }

    private companion object {
        // up between the wordmark and the clock: at (300, 226) the moon sat right behind the seconds
        const val MOON_X = 322f
        const val MOON_Y = 108f
    }
}

/**
 * Everything behind the home screen UI: the user's picture (moved, scaled, dimmed and colour
 * filtered) or a built-in scene, plus the scrims that keep the clock and dock readable.
 * Static: it only redraws when something changes.
 */
class HomeBackdropView(ctx: Context) : View(ctx) {

    var mode = Prefs.HOME_MINIMAL
        set(v) {
            field = v
            rebuildScrims()
            invalidate()
        }

    var scene = Prefs.SCENE_WIRES
        set(v) {
            field = v
            invalidate()
        }

    var image: Bitmap? = null
        set(v) {
            field = v
            invalidate()
        }

    /** Picture centre offset as a fraction of the view size, and zoom over centre-crop. */
    var offX = 0f
    var offY = 0f
    var userScale = 1f

    var dim = 0.15f
    var filter = ImageFilter.WIRED
        set(v) {
            field = v
            imagePaint.colorFilter = v.colorFilter()
            invalidate()
        }

    var clockSide = Prefs.SIDE_LEFT
        set(v) {
            field = v
            rebuildScrims()
            invalidate()
        }

    var accent = Wired.BLUE
        set(v) {
            field = v
            rebuildScrims()
            invalidate()
        }
    var placeholder = "выбери фото в «Композиции»"

    private val nightScene = NightScene()
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val sideScrim = Paint()
    private val topScrim = Paint()
    private val bottomScrim = Paint()
    private val glow = Paint()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        imagePaint.colorFilter = filter.colorFilter()
        text.typeface = Fonts.mono
        text.color = 0xB3D9E9FF.toInt()
        text.textAlign = Paint.Align.CENTER
    }

    fun setTransform(x: Float, y: Float, scale: Float) {
        offX = x
        offY = y
        userScale = scale
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildScrims()
    }

    private fun rebuildScrims() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val v = Wired.VOID and 0x00FFFFFF
        val right = clockSide == Prefs.SIDE_RIGHT
        sideScrim.shader = LinearGradient(
            if (right) w else 0f, 0f, if (right) 0f else w, 0f,
            intArrayOf(v or 0xD1000000.toInt(), v or 0x73000000, v),
            floatArrayOf(0f, 0.38f, 0.66f), Shader.TileMode.CLAMP,
        )
        val topA = if (mode == Prefs.HOME_IMAGE) 0x8C000000.toInt() else 0x99000000.toInt()
        topScrim.shader = LinearGradient(
            0f, 0f, 0f, h * if (mode == Prefs.HOME_IMAGE) 0.18f else 0.3f,
            intArrayOf(v or topA, v), null, Shader.TileMode.CLAMP,
        )
        bottomScrim.shader = LinearGradient(
            0f, h, 0f, h * 0.66f,
            intArrayOf(v or 0xEB000000.toInt(), v), null, Shader.TileMode.CLAMP,
        )
        glow.shader = RadialGradient(
            w * 0.7f, h * 0.38f, max(w, h) * 0.45f,
            intArrayOf(Wired.alpha(accent, 0x40), Wired.alpha(accent, 0)), null, Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        when {
            mode == Prefs.HOME_IMAGE -> drawImage(canvas, w, h)
            scene == Prefs.SCENE_WIRES -> nightScene.draw(canvas, w, h)
            scene == Prefs.SCENE_VOID -> {
                canvas.drawColor(Wired.VOID)
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glow)
            }
            else -> Unit // system wallpaper shows through the window
        }
        val d = when (mode) {
            Prefs.HOME_IMAGE -> dim
            Prefs.HOME_CRT -> 0.45f
            else -> 0.1f
        }
        if (d > 0f) canvas.drawColor(Color.argb((d.coerceIn(0f, 0.9f) * 255).toInt(), 3, 4, 7))
        if (mode == Prefs.HOME_IMAGE) canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), sideScrim)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), topScrim)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bottomScrim)
    }

    private fun drawImage(canvas: Canvas, w: Int, h: Int) {
        canvas.drawColor(Wired.VOID)
        val bmp = image
        if (bmp == null || bmp.isRecycled) {
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glow)
            text.textSize = context.dp(12f)
            canvas.drawText(placeholder, w * 0.62f, h * 0.5f, text)
            return
        }
        val s = max(w.toFloat() / bmp.width, h.toFloat() / bmp.height) * userScale
        matrix.reset()
        matrix.postScale(s, s)
        matrix.postTranslate(
            w / 2f + offX * w - bmp.width * s / 2f,
            h / 2f + offY * h - bmp.height * s / 2f,
        )
        canvas.drawBitmap(bmp, matrix, imagePaint)
    }
}
