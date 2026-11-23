package com.lawyye.wired

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * The user's own pictures: picked with the system photo picker, copied once into private storage
 * (rotation fixed, downscaled), and decoded at the size the screen needs. Nothing leaves the phone.
 */
object ImageStore {
    const val REQ_HOME = 4207
    const val REQ_CRT = 4209

    /** Long edge kept on disk for the home picture: enough headroom to zoom without blur. */
    const val HOME_EDGE = 2600
    const val CRT_EDGE = 1200

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun pick(act: Activity, req: Int, multiple: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val i = Intent(MediaStore.ACTION_PICK_IMAGES)
                i.type = "image/*"
                if (multiple) {
                    i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, Prefs.MAX_CRT.coerceAtMost(MediaStore.getPickImagesMaxLimit()))
                }
                act.startActivityForResult(i, req)
                return
            } catch (t: Throwable) {
                // no photo picker: fall back to the document picker
            }
        }
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "image/*"
            if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            act.startActivityForResult(i, req)
        } catch (t: Throwable) {
            act.toast("Не нашёл галерею для выбора фото")
        }
    }

    fun urisOf(data: Intent?): List<Uri> {
        if (data == null) return emptyList()
        val out = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out.add(it) }
        }
        if (out.isEmpty()) data.data?.let { out.add(it) }
        return out
    }

    /** Copies [uri] into [dst] in the background; [done] runs on the main thread. */
    fun import(ctx: Context, uri: Uri, dst: File, maxEdge: Int, done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val ok = try {
                importBlocking(app, uri, dst, maxEdge)
                true
            } catch (t: Throwable) {
                false
            }
            main.post { done(ok) }
        }
    }

    private fun importBlocking(ctx: Context, uri: Uri, dst: File, maxEdge: Int) {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: error("no stream")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        var bmp: Bitmap = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("decode failed")

        val rotation = try {
            cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (t: Throwable) {
            0
        }
        val m = Matrix()
        if (rotation != 0) m.postRotate(rotation.toFloat())
        val longEdge = max(bmp.width, bmp.height)
        if (longEdge > maxEdge) {
            val s = maxEdge.toFloat() / longEdge
            m.postScale(s, s)
        }
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)

        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (!tmp.renameTo(dst)) {
            tmp.copyTo(dst, overwrite = true)
            tmp.delete()
        }
    }

    /** Decodes [f] so its long edge is about [maxEdge] (never decodes more pixels than needed). */
    fun decode(f: File, maxEdge: Int): Bitmap? = try {
        if (!f.exists()) {
            null
        } else {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.path, o)
            var s = 1
            while (max(o.outWidth, o.outHeight) / (s * 2) >= maxEdge) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            BitmapFactory.decodeFile(f.path, o2)
        }
    } catch (t: Throwable) {
        null
    }

    fun decodeAsync(f: File, maxEdge: Int, done: (Bitmap?) -> Unit) {
        io.execute {
            val b = decode(f, maxEdge)
            main.post { done(b) }
        }
    }
}
