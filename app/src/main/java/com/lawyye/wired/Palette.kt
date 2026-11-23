package com.lawyye.wired

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface

object Palette {
    val VOID: Int = 0xFF030406.toInt()
    val PANEL: Int = 0xF2040609.toInt()
    val TEXT: Int = 0xFFB8CCE8.toInt()
    val BRIGHT: Int = 0xFFE8F0FF.toInt()
    val DIM: Int = 0xFF5D6E88.toInt()
    val RED: Int = 0xFFFF3B4A.toInt()
    val CYAN: Int = 0xFF46E0FF.toInt()
    val LINK: Int = 0xFF4A7DFF.toInt()
    val MAGENTA: Int = 0xFFFF4FD8.toInt()
    val ULTRA: Int = 0xFF3D5AFE.toInt()

    class Accent(val name: String, val color: Int)

    val ACCENTS: List<Accent> = listOf(
        Accent("WIRED BLUE", 0xFF7FB2FF.toInt()),
        Accent("LAYER RED", 0xFFFF3B4A.toInt()),
        Accent("PSYCHE PINK", 0xFFFF6FB5.toInt()),
        Accent("NAVI GREEN", 0xFF7CFFB2.toInt()),
        Accent("PROXY ICE", 0xFF9FE6FF.toInt()),
        Accent("SIGNAL AMBER", 0xFFFFB347.toInt()),
    )

    val mono: Typeface = Typeface.MONOSPACE
    val monoBold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun accent(ctx: Context): Int = ACCENTS[Prefs(ctx).accent.coerceIn(0, ACCENTS.size - 1)].color

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    fun darker(color: Int, f: Float): Int = Color.rgb(
        (Color.red(color) * f).toInt().coerceIn(0, 255),
        (Color.green(color) * f).toInt().coerceIn(0, 255),
        (Color.blue(color) * f).toInt().coerceIn(0, 255),
    )

    /** Grayscale luminance mapped onto black -> [color]. [lift] raises the shadows, [gain] brightens. */
    fun duotone(color: Int, lift: Float, gain: Float): ColorMatrixColorFilter {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val k = (1f - lift) * gain
        return ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0.299f * r * k, 0.587f * r * k, 0.114f * r * k, 0f, lift * r * 255f,
                    0.299f * g * k, 0.587f * g * k, 0.114f * g * k, 0f, lift * g * 255f,
                    0.299f * b * k, 0.587f * b * k, 0.114f * b * k, 0f, lift * b * 255f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    }
}
