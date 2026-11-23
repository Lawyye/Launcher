package com.lawyye.wired

import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("wired", Context.MODE_PRIVATE)

    private fun putInt(k: String, v: Int) {
        sp.edit().putInt(k, v).apply()
    }

    private fun putBool(k: String, v: Boolean) {
        sp.edit().putBoolean(k, v).apply()
    }

    var accent: Int
        get() = sp.getInt("accent", 0)
        set(v) = putInt("accent", v)

    var bgMode: Int
        get() = sp.getInt("bg_mode", BG_VOID)
        set(v) = putInt("bg_mode", v)

    var dim: Int
        get() = sp.getInt("dim", 35)
        set(v) = putInt("dim", v)

    var duotone: Boolean
        get() = sp.getBoolean("duotone", true)
        set(v) = putBool("duotone", v)

    var scanlines: Boolean
        get() = sp.getBoolean("scanlines", true)
        set(v) = putBool("scanlines", v)

    var glitch: Int
        get() = sp.getInt("glitch", 1)
        set(v) = putInt("glitch", v)

    var dataStream: Boolean
        get() = sp.getBoolean("data_stream", true)
        set(v) = putBool("data_stream", v)

    var seconds: Boolean
        get() = sp.getBoolean("seconds", true)
        set(v) = putBool("seconds", v)

    var iconMode: Int
        get() = sp.getInt("icon_mode", ICON_MONO)
        set(v) = putInt("icon_mode", v)

    var nodeStyle: Int
        get() = sp.getInt("node_style", NODE_TERMINAL)
        set(v) = putInt("node_style", v)

    var autoKeyboard: Boolean
        get() = sp.getBoolean("auto_keyboard", false)
        set(v) = putBool("auto_keyboard", v)

    var welcomeShown: Boolean
        get() = sp.getBoolean("welcome_shown", false)
        set(v) = putBool("welcome_shown", v)

    var userHost: String
        get() = sp.getString("user_host", null) ?: "lain@navi"
        set(v) {
            sp.edit().putString("user_host", v).apply()
        }

    var pinned: List<String>
        get() = (sp.getString("pinned", null) ?: "").split('\n').filter { it.isNotBlank() }
        set(v) {
            sp.edit().putString("pinned", v.joinToString("\n")).apply()
        }

    var hidden: Set<String>
        get() = sp.getStringSet("hidden", null)?.toSet() ?: emptySet()
        set(v) {
            sp.edit().putStringSet("hidden", HashSet(v)).apply()
        }

    var wallVersion: Long
        get() = sp.getLong("wall_version", 0L)
        set(v) {
            sp.edit().putLong("wall_version", v).apply()
        }

    val styleVersion: Int
        get() = sp.getInt("style_version", 0)

    fun bumpStyle() {
        putInt("style_version", styleVersion + 1)
    }

    companion object {
        const val BG_VOID = 0
        const val BG_SYSTEM = 1
        const val BG_IMAGE = 2

        const val ICON_NONE = 0
        const val ICON_MONO = 1
        const val ICON_COLOR = 2

        const val NODE_TERMINAL = 0
        const val NODE_LINK = 1

        const val MAX_PINNED = 8
    }
}
