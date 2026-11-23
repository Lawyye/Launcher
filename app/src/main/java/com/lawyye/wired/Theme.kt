package com.lawyye.wired

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface

/** Design tokens of WIRED 2.0 (see the design canvas, "Дизайн-система"). */
object Wired {
    const val VOID = 0xFF030407.toInt()
    const val SURFACE = 0xFF080D17.toInt()
    const val SURFACE2 = 0xFF101827.toInt()
    const val FIELD = 0xFF0B1322.toInt()
    const val DRAWER = 0xFF050913.toInt()
    const val SEGMENT_TRACK = 0xFF04070F.toInt()
    const val TOGGLE_OFF = 0xFF1A2436.toInt()

    const val LINE = 0x2482B9FF
    const val LINE_SOFT = 0x1482B9FF
    const val LINE_STRONG = 0x7382B9FF

    const val BLUE = 0xFF82B9FF.toInt()
    const val CYAN = 0xFFB7F5FF.toInt()
    const val RED = 0xFFFF4565.toInt()
    const val RED_TEXT = 0xFFFF6B85.toInt()
    const val MUTED = 0xFF526987.toInt()
    const val STATIC = 0xFF85899A.toInt()

    const val CLOCK = 0xFFE6F1FF.toInt()
    const val TEXT = 0xFFD9E9FF.toInt()
    const val TEXT_SOFT = 0xFFC9DAF2.toInt()
    const val TEXT2 = 0xFF91A8C8.toInt()
    const val CHIP_TEXT = 0xFFA9BCD6.toInt()
    const val ICON_SOFT = 0xFFB7CBE6.toInt()
    const val HINT = 0xFF7F93B0.toInt()
    const val CHEVRON = 0xFF6F86A6.toInt()

    class Accent(val name: String, val color: Int)

    val ACCENTS: List<Accent> = listOf(
        Accent("Wired Blue", BLUE),
        Accent("Ghost Cyan", CYAN),
        Accent("Signal Red", RED),
        Accent("Psyche Violet", 0xFFA78BFA.toInt()),
        Accent("Phosphor Green", 0xFF7EE787.toInt()),
    )

    fun accent(index: Int): Int = ACCENTS[index.coerceIn(0, ACCENTS.size - 1)].color

    fun alpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    fun alphaF(color: Int, f: Float): Int = alpha(color, (f * 255f).toInt())

    /** Luminance of the source mapped from [dark] to [light]: the "duotone" look of the Wired. */
    fun duotone(dark: Int, light: Int): ColorMatrixColorFilter {
        val dr = Color.red(dark) / 255f
        val dg = Color.green(dark) / 255f
        val db = Color.blue(dark) / 255f
        val kr = Color.red(light) / 255f - dr
        val kg = Color.green(light) / 255f - dg
        val kb = Color.blue(light) / 255f - db
        return ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0.2126f * kr, 0.7152f * kr, 0.0722f * kr, 0f, dr * 255f,
                    0.2126f * kg, 0.7152f * kg, 0.0722f * kg, 0f, dg * 255f,
                    0.2126f * kb, 0.7152f * kb, 0.0722f * kb, 0f, db * 255f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    }
}

/** Colour treatments for the user's images (home picture and CRT window). */
enum class ImageFilter(val title: String, private val dark: Int, private val light: Int) {
    WIRED("Wired", 0xFF03080F.toInt(), 0xFFA9D0FF.toInt()),
    GHOST("Призрак", 0xFF04121A.toInt(), 0xFFD8FBFF.toInt()),
    SIGNAL("Сигнал", 0xFF12040A.toInt(), 0xFFFF9AB0.toInt()),
    MONO("Ч/Б", 0xFF050505.toInt(), 0xFFE8EEF6.toInt()),
    ORIGINAL("Оригинал", 0, 0);

    private var cached: ColorMatrixColorFilter? = null

    fun colorFilter(): ColorMatrixColorFilter? {
        if (this == ORIGINAL) return null
        return cached ?: Wired.duotone(dark, light).also { cached = it }
    }

    companion object {
        fun of(i: Int): ImageFilter = entries.getOrElse(i) { WIRED }
    }
}

/** The three bundled families (SIL Open Font License, see assets/fonts). */
object Fonts {
    private var ready = false

    var clock: Typeface = Typeface.DEFAULT
        private set
    var heading: Typeface = Typeface.DEFAULT
        private set
    var ui: Typeface = Typeface.DEFAULT
        private set
    var uiSemi: Typeface = Typeface.DEFAULT
        private set
    var uiBold: Typeface = Typeface.DEFAULT
        private set
    var mono: Typeface = Typeface.MONOSPACE
        private set
    var monoMedium: Typeface = Typeface.MONOSPACE
        private set

    fun init(ctx: Context) {
        if (ready) return
        ready = true
        val am = ctx.applicationContext.assets
        fun variable(path: String, wght: Int, fallback: Typeface): Typeface = try {
            Typeface.Builder(am, path).setFontVariationSettings("'wght' $wght").build() ?: fallback
        } catch (t: Throwable) {
            fallback
        }
        fun file(path: String, fallback: Typeface): Typeface = try {
            Typeface.createFromAsset(am, path)
        } catch (t: Throwable) {
            fallback
        }
        clock = variable("fonts/SpaceGrotesk.ttf", 300, Typeface.create("sans-serif-light", Typeface.NORMAL))
        heading = variable("fonts/SpaceGrotesk.ttf", 500, Typeface.create("sans-serif-medium", Typeface.NORMAL))
        ui = variable("fonts/Manrope.ttf", 500, Typeface.DEFAULT)
        uiSemi = variable("fonts/Manrope.ttf", 600, Typeface.DEFAULT_BOLD)
        uiBold = variable("fonts/Manrope.ttf", 700, Typeface.DEFAULT_BOLD)
        mono = file("fonts/IBMPlexMono-Regular.ttf", Typeface.MONOSPACE)
        monoMedium = file("fonts/IBMPlexMono-Medium.ttf", Typeface.MONOSPACE)
    }
}
