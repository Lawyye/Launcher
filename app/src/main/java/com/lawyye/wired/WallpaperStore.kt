package com.lawyye.wired

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** Copies a picked image into private storage (downscaled, rotation fixed) so it survives forever. */
object WallpaperStore {
    const val REQ_PICK = 4207

    fun file(ctx: Context): File = File(ctx.filesDir, "wallpaper.jpg")

    fun pickIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
    }

    fun importImage(ctx: Context, uri: Uri, done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            val ok = try {
                importBlocking(app, uri)
                true
            } catch (t: Throwable) {
                false
            }
            main.post { done(ok) }
        }.start()
    }

    private fun importBlocking(ctx: Context, uri: Uri) {
        val cr = ctx.contentResolver
        val dm = ctx.resources.displayMetrics
        val target = max(dm.widthPixels, dm.heightPixels)

        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2

        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        var bmp: Bitmap = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("decode failed")

        val rotation = try {
            cr.openInputStream(uri)!!.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (t: Throwable) {
            0
        }

        val m = Matrix()
        if (rotation != 0) m.postRotate(rotation.toFloat())
        val longEdge = max(bmp.width, bmp.height)
        if (longEdge > target * 1.25f) {
            val s = target * 1.25f / longEdge
            m.postScale(s, s)
        }
        if (!m.isIdentity) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }

        val tmp = File(ctx.filesDir, "wallpaper.tmp")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val dst = file(ctx)
        if (!tmp.renameTo(dst)) {
            tmp.copyTo(dst, overwrite = true)
            tmp.delete()
        }
    }

    fun load(f: File, reqW: Int, reqH: Int): Bitmap? = try {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= reqW && o.outHeight / (s * 2) >= reqH) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        BitmapFactory.decodeFile(f.path, o2)
    } catch (t: Throwable) {
        null
    }
}
