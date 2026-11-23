package com.lawyye.wired

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** All launcher settings. Schema 2 = WIRED 2.0; older installs are migrated once in [migrate]. */
class Prefs(ctx: Context) {

    private val app = ctx.applicationContext
    private val sp: SharedPreferences = app.getSharedPreferences("wired", Context.MODE_PRIVATE)

    init {
        if (sp.getInt("schema", 0) < SCHEMA) migrate()
    }

    private fun int(k: String, d: Int) = sp.getInt(k, d)
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun float(k: String, d: Float) = sp.getFloat(k, d)
    private fun put(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun put(k: String, v: Float) = sp.edit().putFloat(k, v).apply()

    // ------------------------------------------------------------ look

    var accentIndex: Int
        get() = int("accent", 0)
        set(v) = put("accent", v)

    val accent: Int get() = Wired.accent(accentIndex)

    var homeMode: Int
        get() = int("home_mode", HOME_MINIMAL)
        set(v) = put("home_mode", v)

    /** What is behind the clock in CRT and minimal modes. */
    var scene: Int
        get() = int("scene", SCENE_WIRES)
        set(v) = put("scene", v)

    // ------------------------------------------------------------ home picture

    var imgX: Float
        get() = float("img_x", 0f)
        set(v) = put("img_x", v)

    var imgY: Float
        get() = float("img_y", 0f)
        set(v) = put("img_y", v)

    var imgScale: Float
        get() = float("img_scale", 1f)
        set(v) = put("img_scale", v)

    /** 0..80 percent of black over the picture. */
    var imgDim: Int
        get() = int("img_dim", 15)
        set(v) = put("img_dim", v)

    var imgFilter: Int
        get() = int("img_filter", ImageFilter.WIRED.ordinal)
        set(v) = put("img_filter", v)

    var imageVersion: Long
        get() = sp.getLong("image_version", 0L)
        set(v) = sp.edit().putLong("image_version", v).apply()

    fun imageFile(): File = File(app.filesDir, "wallpaper.jpg")

    // ------------------------------------------------------------ clock

    var clockStyle: Int
        get() = int("clock_style", CLOCK_STACK)
        set(v) = put("clock_style", v)

    var clockSide: Int
        get() = int("clock_side", SIDE_LEFT)
        set(v) = put("clock_side", v)

    var showInfo: Boolean
        get() = bool("show_info", true)
        set(v) = put("show_info", v)

    var seconds: Boolean
        get() = bool("seconds2", false)
        set(v) = put("seconds2", v)

    // ------------------------------------------------------------ effects and performance

    var profile: Int
        get() = int("profile", PROFILE_BALANCE)
        set(v) = put("profile", v)

    var scanlines: Boolean
        get() = bool("scanlines", true)
        set(v) = put("scanlines", v)

    var grain: Boolean
        get() = bool("grain", true)
        set(v) = put("grain", v)

    var aberration: Boolean
        get() = bool("aberration", false)
        set(v) = put("aberration", v)

    var glitchUnlock: Boolean
        get() = bool("glitch_unlock", true)
        set(v) = put("glitch_unlock", v)

    var glitchTap: Boolean
        get() = bool("glitch_tap", true)
        set(v) = put("glitch_tap", v)

    val effectsOn: Boolean get() = profile != PROFILE_ECO
    val animationsOn: Boolean get() = profile != PROFILE_ECO
    val continuousOn: Boolean get() = profile == PROFILE_VISUAL

    // ------------------------------------------------------------ apps

    var iconMode: Int
        get() = int("icon_mode2", ICON_ORIGINAL)
        set(v) = put("icon_mode2", v)

    var iconSizeDp: Int
        get() = int("icon_size", 56)
        set(v) = put("icon_size", v)

    var drawerMode: Int
        get() = int("drawer_mode", DRAWER_GRID)
        set(v) = put("drawer_mode", v)

    var columns: Int
        get() = int("columns", 4)
        set(v) = put("columns", v)

    /** Swipe down on the home screen opens the WIRED shade instead of the system one. */
    var ownShade: Boolean
        get() = bool("own_shade", true)
        set(v) = put("own_shade", v)

    var autoKeyboard: Boolean
        get() = bool("auto_keyboard", false)
        set(v) = put("auto_keyboard", v)

    var dock: List<String>
        get() = (sp.getString("dock", null) ?: "").split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("dock", v.take(MAX_DOCK).joinToString("\n")).apply()

    var dockLabels: Boolean
        get() = bool("dock_labels", false)
        set(v) = put("dock_labels", v)

    var hidden: Set<String>
        get() = sp.getStringSet("hidden", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("hidden", HashSet(v)).apply()

    fun launchCounts(): Map<String, Int> {
        val raw = sp.getString("launch_counts", null) ?: return emptyMap()
        val out = HashMap<String, Int>()
        for (line in raw.split('\n')) {
            val tab = line.lastIndexOf('\t')
            if (tab <= 0) continue
            val n = line.substring(tab + 1).toIntOrNull() ?: continue
            out[line.substring(0, tab)] = n
        }
        return out
    }

    fun countLaunch(key: String) {
        val m = HashMap(launchCounts())
        m[key] = (m[key] ?: 0) + 1
        // keep the map small: forget the least used when it grows
        val kept = if (m.size > 160) m.entries.sortedByDescending { it.value }.take(120).associate { it.key to it.value } else m
        sp.edit().putString("launch_counts", kept.entries.joinToString("\n") { it.key + "\t" + it.value }).apply()
    }

    // ------------------------------------------------------------ CRT window

    var crtImages: List<String>
        get() = (sp.getString("crt_images", null) ?: "").split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("crt_images", v.joinToString("\n")).apply()

    var crtIndex: Int
        get() = int("crt_index", 0)
        set(v) = put("crt_index", v)

    var crtFilter: Int
        get() = int("crt_filter", ImageFilter.WIRED.ordinal)
        set(v) = put("crt_filter", v)

    var crtRotate: Boolean
        get() = bool("crt_rotate", true)
        set(v) = put("crt_rotate", v)

    fun crtDir(): File = File(app.filesDir, "crt").also { it.mkdirs() }

    // ------------------------------------------------------------ misc

    var welcomeShown: Boolean
        get() = bool("welcome2", false)
        set(v) = put("welcome2", v)

    val styleVersion: Int get() = int("style_version", 0)

    fun bumpStyle() = put("style_version", styleVersion + 1)

    private fun migrate() {
        val e = sp.edit()
        val old = sp.getInt("schema", 0)
        if (old < 2) {
            // accent indices of v1: blue, red, pink, green, ice, amber
            val map = intArrayOf(0, 2, 3, 4, 1, 0)
            val a = sp.getInt("accent", 0)
            e.putInt("accent", map.getOrElse(a) { 0 })
            // the v1 default tinted every icon into the same blue silhouette: back to real icons
            e.putInt("icon_mode2", ICON_ORIGINAL)
            val bg = sp.getInt("bg_mode", 0)
            val hasImage = imageFile().exists()
            when {
                bg == 2 && hasImage -> e.putInt("home_mode", HOME_IMAGE)
                bg == 1 -> {
                    e.putInt("home_mode", HOME_MINIMAL)
                    e.putInt("scene", SCENE_SYSTEM)
                }
                else -> e.putInt("home_mode", HOME_MINIMAL)
            }
            e.putInt("img_filter", if (sp.getBoolean("duotone", true)) ImageFilter.WIRED.ordinal else ImageFilter.ORIGINAL.ordinal)
            e.putInt("img_dim", (sp.getInt("dim", 30) / 2).coerceIn(0, 80))
            val pinned = (sp.getString("pinned", null) ?: "").split('\n').filter { it.isNotBlank() }
            if (pinned.isNotEmpty()) e.putString("dock", pinned.take(MAX_DOCK).joinToString("\n"))
            if (sp.getInt("glitch", 1) == 0) {
                e.putBoolean("glitch_unlock", false)
                e.putBoolean("glitch_tap", false)
            }
            e.putInt("style_version", sp.getInt("style_version", 0) + 1)
        }
        e.putInt("schema", SCHEMA)
        e.apply()
    }

    companion object {
        const val SCHEMA = 2

        const val HOME_IMAGE = 0
        const val HOME_CRT = 1
        const val HOME_MINIMAL = 2

        const val SCENE_WIRES = 0
        const val SCENE_VOID = 1
        const val SCENE_SYSTEM = 2

        const val CLOCK_STACK = 0
        const val CLOCK_LINE = 1

        const val SIDE_LEFT = 0
        const val SIDE_RIGHT = 1

        const val PROFILE_ECO = 0
        const val PROFILE_BALANCE = 1
        const val PROFILE_VISUAL = 2

        const val ICON_ORIGINAL = 0
        const val ICON_TINT = 1
        const val ICON_MONO = 2

        const val DRAWER_GRID = 0
        const val DRAWER_LIST = 1

        const val MAX_DOCK = 5
        const val MAX_CRT = 8
    }
}
