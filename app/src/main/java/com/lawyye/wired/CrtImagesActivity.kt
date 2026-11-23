package com.lawyye.wired

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File

/** "CRT-окно": the pictures shown in the monitor widget, its colour filter and rotation. */
class CrtImagesActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var scroll: ScrollView
    private lateinit var preview: CrtWindowView
    private var accent = Wired.BLUE
    private var importing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        prefs = Prefs(this)
        edgeToEdge()
        scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.setBackgroundColor(Wired.VOID)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val b = systemInsets(insets)
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(scroll)
        build()
        preview.post { preview.powerOn(prefs.animationsOn) }
    }

    override fun onResume() {
        super.onResume()
        preview.onVisible(true)
    }

    override fun onPause() {
        super.onPause()
        preview.onVisible(false)
    }

    private fun build() {
        accent = prefs.accent
        scroll.removeAllViews()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dpi(20), dpi(12), dpi(20), dpi(40))
        scroll.addView(col)

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(iconButton(R.drawable.ic_back, "Назад") { finish() })
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        val t = label(26f, Wired.CLOCK, Fonts.uiBold)
        t.text = "CRT-окно"
        titles.addView(t)
        titles.addView(meta("твои картинки в мониторе · до " + Prefs.MAX_CRT))
        val tlp = LinearLayout.LayoutParams(0, WRAP, 1f)
        tlp.leftMargin = dpi(12)
        head.addView(titles, tlp)
        col.addView(head)

        // live preview of the widget
        preview = CrtWindowView(this)
        preview.accent = accent
        preview.filter = ImageFilter.of(prefs.crtFilter)
        preview.continuous = prefs.continuousOn
        preview.emptyText = "добавь картинку ниже"
        preview.setOnClickListener { next() }
        val plp = LinearLayout.LayoutParams(MATCH, dpi(300))
        plp.topMargin = dpi(20)
        col.addView(preview, plp)
        loadPreview(false)

        // thumbnails
        val names = prefs.crtImages
        val label = meta("картинки · " + names.size + " · нажми, чтобы показать или удалить")
        label.setPadding(dpi(4), dpi(26), 0, dpi(10))
        col.addView(label)
        val cells = names.map { n -> thumb(n) }.toMutableList<View>()
        if (names.size < Prefs.MAX_CRT) cells.add(addTile())
        cells.chunked(3).forEach { rowCells ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            rowCells.forEachIndexed { i, v ->
                val lp = LinearLayout.LayoutParams(0, dpi(150), 1f)
                if (i > 0) lp.leftMargin = dpi(10)
                row.addView(v, lp)
            }
            repeat(3 - rowCells.size) { row.addView(View(this), LinearLayout.LayoutParams(0, dpi(150), 1f).also { it.leftMargin = dpi(10) }) }
            val rlp = LinearLayout.LayoutParams(MATCH, WRAP)
            rlp.bottomMargin = dpi(10)
            col.addView(row, rlp)
        }

        // filter
        val fl = meta("цвет")
        fl.setPadding(dpi(4), dpi(16), 0, dpi(10))
        col.addView(fl)
        val chipsScroll = HorizontalScrollView(this)
        chipsScroll.isHorizontalScrollBarEnabled = false
        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        val chipViews = ArrayList<android.widget.TextView>()
        ImageFilter.entries.forEachIndexed { i, f ->
            val c = chip(f.title, f.ordinal == prefs.crtFilter, accent) {
                prefs.crtFilter = f.ordinal
                prefs.bumpStyle()
                preview.filter = f
                chipViews.forEachIndexed { k, ch -> styleChip(ch, k == i, accent) }
            }
            val lp = LinearLayout.LayoutParams(WRAP, dpi(36))
            if (i > 0) lp.leftMargin = dpi(6)
            chips.addView(c, lp)
            chipViews.add(c)
        }
        chipsScroll.addView(chips)
        col.addView(chipsScroll)

        val g = Group(this, "поведение")
        g.add(settingsRow(R.drawable.ic_power, "Новая картинка при разблокировке", accent,
            trailing = wiredSwitch(prefs.crtRotate, accent, "Новая картинка при разблокировке") { prefs.crtRotate = it }))
        col.addView(g)

        val show = primaryButton(if (prefs.homeMode == Prefs.HOME_CRT) "Готово" else "Показать CRT-окно на главном", accent) {
            prefs.homeMode = Prefs.HOME_CRT
            prefs.bumpStyle()
            finish()
        }
        val slp = LinearLayout.LayoutParams(MATCH, dpi(52))
        slp.topMargin = dpi(24)
        col.addView(show, slp)
        val hint = meta("нажатие на монитор на главном — следующая картинка · удержание — сюда")
        hint.setTextColor(Wired.STATIC)
        hint.gravity = Gravity.CENTER
        hint.setPadding(0, dpi(10), 0, 0)
        col.addView(hint)
        preview.onVisible(true)
    }

    private fun loadPreview(animate: Boolean) {
        val names = prefs.crtImages
        if (names.isEmpty()) {
            preview.image = null
            if (animate) preview.powerOn(prefs.animationsOn)
            return
        }
        val n = names[prefs.crtIndex.mod(names.size)]
        ImageStore.decodeAsync(File(prefs.crtDir(), n), 900) { b ->
            if (isDestroyed) return@decodeAsync
            preview.image = b
            if (animate) preview.powerOn(prefs.animationsOn)
        }
    }

    private fun next() {
        val names = prefs.crtImages
        if (names.isEmpty()) {
            ImageStore.pick(this, ImageStore.REQ_CRT, true)
            return
        }
        prefs.crtIndex = (prefs.crtIndex + 1).mod(names.size)
        loadPreview(true)
    }

    private fun thumb(name: String): View {
        val f = FrameLayout(this)
        f.background = rounded(Wired.SURFACE, 14f, Wired.LINE)
        f.clipToOutline = true
        f.isClickable = true
        f.isFocusable = true
        f.contentDescription = "Картинка CRT-окна"
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.colorFilter = ImageFilter.of(prefs.crtFilter).colorFilter()
        f.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        ImageStore.decodeAsync(File(prefs.crtDir(), name), 360) { b -> if (!isDestroyed) iv.setImageBitmap(b) }
        f.setOnClickListener {
            WiredDialog(this, accent)
                .option(R.drawable.ic_monitor, "Показать сейчас") {
                    prefs.crtIndex = prefs.crtImages.indexOf(name).coerceAtLeast(0)
                    loadPreview(true)
                }
                .option(R.drawable.ic_trash, "Удалить", danger = true) {
                    prefs.crtImages = prefs.crtImages - name
                    File(prefs.crtDir(), name).delete()
                    prefs.bumpStyle()
                    build()
                }
                .show()
        }
        return f
    }

    private fun addTile(): View {
        val f = LinearLayout(this)
        f.orientation = LinearLayout.VERTICAL
        f.gravity = Gravity.CENTER
        f.background = ripple(rounded(0, 14f, 0x4D82B9FF), 14f, accent)
        f.isClickable = true
        f.isFocusable = true
        f.contentDescription = "Добавить картинки"
        f.addView(iconView(R.drawable.ic_plus, accent, 24))
        val t = meta("добавить")
        t.setPadding(0, dpi(8), 0, 0)
        f.addView(t)
        f.setOnClickListener {
            if (!importing) ImageStore.pick(this, ImageStore.REQ_CRT, true)
        }
        return f
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != ImageStore.REQ_CRT || resultCode != RESULT_OK) return
        val free = Prefs.MAX_CRT - prefs.crtImages.size
        val uris = ImageStore.urisOf(data).take(free.coerceAtLeast(0))
        if (uris.isEmpty()) {
            if (free <= 0) toast("Максимум " + Prefs.MAX_CRT + " картинок")
            return
        }
        importing = true
        toast("Добавляю…")
        importNext(uris, 0, ArrayList())
    }

    private fun importNext(uris: List<Uri>, i: Int, added: ArrayList<String>) {
        if (i >= uris.size) {
            importing = false
            if (added.isNotEmpty()) {
                prefs.crtImages = prefs.crtImages + added
                prefs.crtIndex = prefs.crtImages.size - added.size
                prefs.bumpStyle()
            }
            if (!isDestroyed) {
                build()
                preview.post { preview.powerOn(prefs.animationsOn) }
            }
            return
        }
        val name = "crt_" + System.currentTimeMillis() + "_" + i + ".jpg"
        ImageStore.import(this, uris[i], File(prefs.crtDir(), name), ImageStore.CRT_EDGE) { ok ->
            if (ok) added.add(name)
            importNext(uris, i + 1, added)
        }
    }
}
