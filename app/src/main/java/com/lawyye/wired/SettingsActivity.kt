package com.lawyye.wired

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private lateinit var crt: CrtOverlayView
    private var accent = Palette.TEXT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        edgeToEdge()

        val root = FrameLayout(this)
        root.setBackgroundColor(Palette.VOID)
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.isFillViewport = true
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        scroll.addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        crt = CrtOverlayView(this)
        root.addView(crt, FrameLayout.LayoutParams(MATCH, MATCH))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val s = systemInsets(insets)
            list.setPadding(dpi(22) + s.left, dpi(12) + s.top, dpi(22) + s.right, dpi(28) + s.bottom)
            insets
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != WallpaperStore.REQ_PICK) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            if (prefs.bgMode == Prefs.BG_IMAGE && !WallpaperStore.file(this).exists()) {
                prefs.bgMode = Prefs.BG_VOID
                changed()
            }
            return
        }
        toast("DECODING IMAGE...")
        WallpaperStore.importImage(this, uri) { ok ->
            if (ok) {
                prefs.bgMode = Prefs.BG_IMAGE
                prefs.wallVersion = System.currentTimeMillis()
                changed()
                toast("WALLPAPER LOADED")
            } else {
                toast("READ ERROR")
            }
        }
    }

    private fun changed() {
        prefs.bumpStyle()
        if (!isFinishing && !isDestroyed) render()
    }

    private fun pick() {
        try {
            startActivityForResult(WallpaperStore.pickIntent(), WallpaperStore.REQ_PICK)
        } catch (e: Throwable) {
            toast("NO FILE PICKER FOUND")
        }
    }

    // ------------------------------------------------------------------ rows

    private fun section(title: String) {
        val tv = monoText(10f, accent)
        tv.text = "// $title"
        tv.setPadding(0, dpi(24), 0, dpi(6))
        list.addView(tv, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun row(label: String, value: String, valueColor: Int = accent, onClick: () -> Unit) {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dpi(4), dpi(12), dpi(4), dpi(12))
        r.background = pressBg(accent)
        r.setOnClickListener { onClick() }
        val l = monoText(13f, Palette.TEXT)
        l.text = label
        val v = monoText(13f, valueColor)
        v.text = value
        r.addView(l, LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(v)
        list.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun cycle(label: String, options: List<String>, current: Int, valueColor: Int = accent, set: (Int) -> Unit) {
        val cur = current.coerceIn(0, options.size - 1)
        row(label, "< " + options[cur] + " >", valueColor) {
            set((cur + 1) % options.size)
            changed()
        }
    }

    private fun toggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
        row(label, if (on) "[ON]" else "[OFF]", if (on) accent else Palette.DIM) {
            set(!on)
            changed()
        }
    }

    private fun render() {
        accent = Palette.accent(this)
        crt.configure(prefs.scanlines, accent)
        list.removeAllViews()

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = monoText(17f, Palette.BRIGHT, bold = true)
        title.text = "NAVI://CONFIG"
        head.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        val close = monoText(14f, accent)
        close.text = "[x]"
        close.setPadding(dpi(14), dpi(12), dpi(4), dpi(12))
        close.setOnClickListener { finish() }
        head.addView(close)
        list.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

        val ver = monoText(10f, Palette.DIM)
        ver.text = "wired launcher // v" + BuildConfig.VERSION_NAME
        list.addView(ver, LinearLayout.LayoutParams(MATCH, WRAP))
        val ruleLp = LinearLayout.LayoutParams(MATCH, dpi(1))
        ruleLp.topMargin = dpi(12)
        val rule = View(this)
        rule.setBackgroundColor(Palette.withAlpha(accent, 90))
        list.addView(rule, ruleLp)

        section("SYSTEM")
        val isDefault = HomeRole.isDefault(this)
        row("DEFAULT HOME", if (isDefault) "[ACTIVE]" else "[SET]", if (isDefault) Palette.DIM else accent) {
            HomeRole.request(this)
        }
        row("SYSTEM SETTINGS", "[OPEN]") { AppActions.safeStart(this, Intent(Settings.ACTION_SETTINGS)) }

        section("BACKGROUND")
        val hasImage = WallpaperStore.file(this).exists()
        cycle("MODE", listOf("VOID", "SYSTEM", "IMAGE"), prefs.bgMode) { mode ->
            prefs.bgMode = mode
            if (mode == Prefs.BG_IMAGE && !hasImage) pick()
        }
        row("IMAGE", if (hasImage) "[CHANGE]" else "[BROWSE]") { pick() }
        toggle("DUOTONE IMAGE", prefs.duotone) { prefs.duotone = it }
        val dimIdx = DIMS.indexOf(prefs.dim).let { if (it < 0) 2 else it }
        cycle("DIM", DIMS.map { "$it%" }, dimIdx) { prefs.dim = DIMS[it] }

        section("SIGNAL")
        val acc = prefs.accent.coerceIn(0, Palette.ACCENTS.size - 1)
        cycle("ACCENT", Palette.ACCENTS.map { it.name }, acc, Palette.ACCENTS[acc].color) { prefs.accent = it }
        toggle("SCANLINES", prefs.scanlines) { prefs.scanlines = it }
        cycle("GLITCH", listOf("OFF", "LOW", "HIGH"), prefs.glitch) { prefs.glitch = it }
        toggle("DATA STREAM", prefs.dataStream) { prefs.dataStream = it }
        toggle("CLOCK SECONDS", prefs.seconds) { prefs.seconds = it }

        section("THE WIRED")
        cycle("ICONS", listOf("NONE", "MONO", "COLOR"), prefs.iconMode) { prefs.iconMode = it }
        cycle("PINNED STYLE", listOf("TERMINAL", "HYPERLINK"), prefs.nodeStyle) { prefs.nodeStyle = it }
        toggle("AUTO KEYBOARD", prefs.autoKeyboard) { prefs.autoKeyboard = it }
        row("USER@HOST", prefs.userHost) { editUserHost() }
        val hiddenCount = prefs.hidden.size
        row("HIDDEN APPS", if (hiddenCount == 0) "[NONE]" else "[RESTORE $hiddenCount]", if (hiddenCount == 0) Palette.DIM else accent) {
            if (hiddenCount > 0) {
                prefs.hidden = emptySet()
                changed()
                toast("ALL NODES RESTORED")
            }
        }

        section("ABOUT")
        val about = monoText(10f, Palette.DIM)
        about.text = "source: github.com/Lawyye/launcher\n" +
            "no artwork bundled — set your own wallpaper.\n" +
            "swipe up: apps · swipe down: notifications\n" +
            "hold empty space: menu · double tap: glitch"
        about.setLineSpacing(0f, 1.45f)
        list.addView(about, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun editUserHost() {
        val d = WinDialog(this, "IDENTITY.CFG", accent).message("who are you?")
        val input = d.input(prefs.userHost, "user@host")
        d.buttons("CANCEL", "OK") {
            val v = input.text.toString().trim().take(24)
            if (v.isNotEmpty()) {
                prefs.userHost = v
                changed()
            }
        }
        d.show()
    }

    companion object {
        private val DIMS = listOf(0, 20, 35, 50, 65, 80)
    }
}
