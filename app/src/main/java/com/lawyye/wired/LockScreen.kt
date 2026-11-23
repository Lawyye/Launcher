package com.lawyye.wired

import android.app.Activity
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A launcher cannot replace Android's lock screen, but it can paint it: the current WIRED
 * composition (picture, CRT picture or the "Провода" scene, with darkening, colour filter,
 * scanlines and grain) is rendered at screen size and set as the lock screen wallpaper.
 * The clock and notifications there are still drawn by Samsung.
 */
object LockScreen {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    const val LOCK = WallpaperManager.FLAG_LOCK
    const val SYSTEM = WallpaperManager.FLAG_SYSTEM

    fun applyCurrent(act: Activity, which: Int, done: (Boolean) -> Unit) {
        val p = Prefs(act)
        when {
            p.homeMode == Prefs.HOME_IMAGE && p.imageFile().exists() ->
                applyPicture(act, p.imageFile(), p.imgX, p.imgY, p.imgScale, p.imgDim, ImageFilter.of(p.imgFilter), p, which, done)
            p.homeMode == Prefs.HOME_CRT && p.crtImages.isNotEmpty() -> {
                val name = p.crtImages[p.crtIndex.mod(p.crtImages.size)]
                applyPicture(act, File(p.crtDir(), name), 0f, 0f, 1f, 25, ImageFilter.of(p.crtFilter), p, which, done)
            }
            else -> applyScene(act, p, which, done)
        }
    }

    fun applyPicture(
        act: Activity, file: File, x: Float, y: Float, scale: Float, dimPct: Int,
        filter: ImageFilter, prefs: Prefs, which: Int, done: (Boolean) -> Unit,
    ) {
        val (w, h) = screenSize(act)
        val app = act.applicationContext
        val fx = prefs.effectsOn
        val scan = prefs.scanlines
        val grain = prefs.grain
        val density = act.resources.displayMetrics.density
        io.execute {
            val ok = try {
                val src = ImageStore.decode(file, (max(w, h) * 1.25f).toInt()) ?: error("decode")
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                c.drawColor(Wired.VOID)
                val s = max(w.toFloat() / src.width, h.toFloat() / src.height) * scale
                val m = Matrix()
                m.postScale(s, s)
                m.postTranslate(w / 2f + x * w - src.width * s / 2f, h / 2f + y * h - src.height * s / 2f)
                val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                p.colorFilter = filter.colorFilter()
                c.drawBitmap(src, m, p)
                finish(c, w, h, dimPct / 100f, fx, scan, grain, density)
                WallpaperManager.getInstance(app).setBitmap(out, null, true, which)
                true
            } catch (t: Throwable) {
                false
            }
            main.post { done(ok) }
        }
    }

    fun applyScene(act: Activity, prefs: Prefs, which: Int, done: (Boolean) -> Unit) {
        val (w, h) = screenSize(act)
        val app = act.applicationContext
        val fx = prefs.effectsOn
        val scan = prefs.scanlines
        val grain = prefs.grain
        val density = act.resources.displayMetrics.density
        io.execute {
            val ok = try {
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                NightScene().draw(c, w, h)
                finish(c, w, h, 0.1f, fx, scan, grain, density)
                WallpaperManager.getInstance(app).setBitmap(out, null, true, which)
                true
            } catch (t: Throwable) {
                false
            }
            main.post { done(ok) }
        }
    }

    /** Darkening, soft scrims for the system clock, and the static CRT layer. */
    private fun finish(c: Canvas, w: Int, h: Int, dim: Float, fx: Boolean, scan: Boolean, grain: Boolean, d: Float) {
        if (dim > 0f) c.drawColor(Color.argb((dim.coerceIn(0f, 0.9f) * 255).toInt(), 3, 4, 7))
        val v = Wired.VOID and 0x00FFFFFF
        val top = Paint()
        top.shader = LinearGradient(0f, 0f, 0f, h * 0.32f, intArrayOf(v or 0x8C000000.toInt(), v), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), top)
        val bottom = Paint()
        bottom.shader = LinearGradient(0f, h.toFloat(), 0f, h * 0.72f, intArrayOf(v or 0xA6000000.toInt(), v), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bottom)
        if (!fx) return
        if (scan) {
            val sp = Paint()
            sp.shader = BitmapShader(EffectTiles.lines((3 * d).roundToInt(), d.roundToInt(), 0x09B7F5FF), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), sp)
        }
        if (grain) {
            val gp = Paint()
            gp.shader = BitmapShader(EffectTiles.grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            gp.alpha = 15
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), gp)
        }
        val vg = Paint()
        vg.shader = RadialGradient(w / 2f, h * 0.45f, max(w, h) * 0.75f, intArrayOf(0, 0, 0x80000000.toInt()), floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), vg)
    }

    @Suppress("DEPRECATION")
    private fun screenSize(act: Activity): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = act.windowManager.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            act.windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }
}
