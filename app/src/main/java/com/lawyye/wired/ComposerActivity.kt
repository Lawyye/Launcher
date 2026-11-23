package com.lawyye.wired

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.util.Calendar
import java.util.Locale

/**
 * "Композиция": the user's picture under the real clock. Drag to move, pinch to zoom,
 * double tap to reset; darkening, colour filter and clock side below. Saved only on "Готово".
 */
class ComposerActivity : Activity() {

    private lateinit var prefs: Prefs
    private var accent = Wired.BLUE

    private lateinit var backdrop: HomeBackdropView
    private lateinit var clockBlock: LinearLayout
    private lateinit var bar: View
    private lateinit var textCol: LinearLayout
    private lateinit var clock: ClockView
    private lateinit var topBar: LinearLayout
    private lateinit var sheet: LinearLayout
    private lateinit var sourceMeta: TextView
    private lateinit var scaleValue: TextView
    private lateinit var dimValue: TextView
    private lateinit var scaleBar: SeekBar
    private lateinit var dimBar: SeekBar
    private val filterChips = ArrayList<TextView>()
    private lateinit var sideSeg: Segmented

    private var x = 0f
    private var y = 0f
    private var scale = 1f
    private var dim = 15
    private var filter = ImageFilter.WIRED
    private var side = Prefs.SIDE_LEFT
    private var newImage = false
    private var autoPicked = false
    private var alsoLock = false

    private val temp: File get() = File(filesDir, "wallpaper_new.jpg")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        prefs = Prefs(this)
        accent = prefs.accent
        x = prefs.imgX
        y = prefs.imgY
        scale = prefs.imgScale
        dim = prefs.imgDim
        filter = ImageFilter.of(prefs.imgFilter)
        side = prefs.clockSide
        edgeToEdge()
        setContentView(build())
        applyAll()
        val f = prefs.imageFile()
        if (f.exists()) {
            loadPreview(f)
        } else if (!autoPicked) {
            autoPicked = true
            ImageStore.pick(this, ImageStore.REQ_HOME, false)
        }
    }

    private fun loadPreview(f: File) {
        val dm = resources.displayMetrics
        ImageStore.decodeAsync(f, (maxOf(dm.widthPixels, dm.heightPixels) * 1.25f).toInt()) { b ->
            if (!isDestroyed) backdrop.image = b
        }
        sourceMeta.text = if (newImage) "новое фото" else "твоё фото"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != ImageStore.REQ_HOME || resultCode != RESULT_OK) return
        val uri = ImageStore.urisOf(data).firstOrNull() ?: return
        sourceMeta.text = "декодирую…"
        ImageStore.import(this, uri, temp, ImageStore.HOME_EDGE) { ok ->
            if (!ok) {
                toast("Не получилось прочитать картинку")
                sourceMeta.text = "ошибка чтения"
                return@import
            }
            newImage = true
            x = 0f
            y = 0f
            scale = 1f
            applyAll()
            loadPreview(temp)
        }
    }

    private fun save() {
        if (newImage) {
            val dst = prefs.imageFile()
            if (dst.exists()) dst.delete()
            if (!temp.renameTo(dst)) {
                temp.copyTo(dst, overwrite = true)
                temp.delete()
            }
            prefs.imageVersion = System.currentTimeMillis()
        }
        if (!prefs.imageFile().exists()) {
            toast("Сначала выбери фото")
            return
        }
        prefs.imgX = x
        prefs.imgY = y
        prefs.imgScale = scale
        prefs.imgDim = dim
        prefs.imgFilter = filter.ordinal
        prefs.clockSide = side
        prefs.homeMode = Prefs.HOME_IMAGE
        prefs.bumpStyle()
        if (alsoLock) {
            toast("Ставлю на экран блокировки…")
            LockScreen.applyCurrent(this, LockScreen.LOCK) { ok ->
                applicationContext.toast(if (ok) "Экран блокировки обновлён" else "Не получилось поставить на блокировку")
            }
        }
        finish()
    }

    private fun cancel() {
        if (temp.exists()) temp.delete()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = cancel()

    // ------------------------------------------------------------ layout

    @SuppressLint("ClickableViewAccessibility")
    private fun build(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(Wired.VOID)
        backdrop = HomeBackdropView(this)
        backdrop.mode = Prefs.HOME_IMAGE
        backdrop.accent = accent
        root.addView(backdrop, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(GuidesView(this), FrameLayout.LayoutParams(MATCH, MATCH))

        // the same clock block as on the home screen, to judge the composition
        clockBlock = LinearLayout(this)
        clockBlock.orientation = LinearLayout.HORIZONTAL
        bar = View(this)
        bar.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(accent, Wired.alpha(accent, 0)))
        textCol = LinearLayout(this)
        textCol.orientation = LinearLayout.VERTICAL
        clock = ClockView(this)
        clock.stacked = true
        clock.maxSizeSp = 128f
        clock.accent = accent
        val cal = Calendar.getInstance()
        clock.setTime(
            String.format(Locale.US, "%02d", cal.get(Calendar.HOUR_OF_DAY)),
            String.format(Locale.US, "%02d", cal.get(Calendar.MINUTE)), "",
        )
        textCol.addView(clock)
        val date = label(15f, Wired.TEXT_SOFT, Fonts.ui)
        date.text = java.text.SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(cal.time).lowercase()
        val dlp = LinearLayout.LayoutParams(WRAP, WRAP)
        dlp.topMargin = dpi(16)
        textCol.addView(date, dlp)
        root.addView(clockBlock, FrameLayout.LayoutParams(WRAP, WRAP))

        // gestures: drag, pinch, double tap
        val surface = View(this)
        surface.contentDescription = "Положение картинки: перетаскивай, щипком меняй размер"
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scale = (scale * detector.scaleFactor).coerceIn(0.6f, 3f)
                applyTransform()
                return true
            }
        })
        val moveDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (backdrop.width == 0) return false
                x = (x - dx / backdrop.width).coerceIn(-1f, 1f)
                y = (y - dy / backdrop.height).coerceIn(-1f, 1f)
                applyTransform()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                x = 0f
                y = 0f
                scale = 1f
                applyTransform()
                return true
            }
        })
        surface.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            if (!scaleDetector.isInProgress) moveDetector.onTouchEvent(ev)
            true
        }
        root.addView(surface, FrameLayout.LayoutParams(MATCH, MATCH))

        // top bar
        topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.addView(iconButton(R.drawable.ic_back, "Отмена") { cancel() })
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.gravity = Gravity.CENTER_HORIZONTAL
        val t = label(15f, Wired.CLOCK, Fonts.uiSemi)
        t.text = "Композиция"
        titles.addView(t)
        sourceMeta = meta("пример · выбери фото", 10f)
        titles.addView(sourceMeta)
        titles.background = rounded(0x99080D17.toInt(), 14f)
        titles.setPadding(dpi(14), dpi(6), dpi(14), dpi(6))
        val tw = LinearLayout(this)
        tw.gravity = Gravity.CENTER
        tw.addView(titles)
        topBar.addView(tw, LinearLayout.LayoutParams(0, WRAP, 1f))
        val done = primaryButton("Готово", accent) { save() }
        done.minHeight = dpi(44)
        topBar.addView(done, LinearLayout.LayoutParams(WRAP, dpi(44)))
        root.addView(topBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        // bottom sheet
        sheet = LinearLayout(this)
        sheet.orientation = LinearLayout.VERTICAL
        val sbg = GradientDrawable()
        sbg.setColor(0xE6060A13.toInt())
        val r = dp(28f)
        sbg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        sbg.setStroke(dpi(1), 0x2982B9FF)
        sheet.background = sbg
        sheet.isClickable = true
        val handle = View(this)
        handle.background = rounded(0x38D9E9FF, 2f)
        val hlp = LinearLayout.LayoutParams(dpi(36), dpi(4))
        hlp.gravity = Gravity.CENTER_HORIZONTAL
        sheet.addView(handle, hlp)

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        val pick = primaryButton("Выбрать фото", accent) { ImageStore.pick(this, ImageStore.REQ_HOME, false) }
        pick.minHeight = dpi(44)
        buttons.addView(pick, LinearLayout.LayoutParams(0, dpi(44), 1f))
        val reset = ghostButton("Сброс") {
            x = 0f
            y = 0f
            scale = 1f
            applyTransform()
        }
        val rlp = LinearLayout.LayoutParams(WRAP, dpi(44))
        rlp.leftMargin = dpi(10)
        buttons.addView(reset, rlp)
        val blp = LinearLayout.LayoutParams(MATCH, WRAP)
        blp.topMargin = dpi(12)
        sheet.addView(buttons, blp)

        scaleValue = meta("")
        scaleBar = wiredSlider(240, 40, accent) { p ->
            scale = 0.6f + p / 100f
            applyTransform()
        }
        sheet.addView(sliderBlock("Масштаб", scaleValue, scaleBar))
        dimValue = meta("")
        dimBar = wiredSlider(80, dim, accent) { p ->
            dim = p
            backdrop.dim = p / 100f
            backdrop.invalidate()
            dimValue.text = "$p%"
        }
        sheet.addView(sliderBlock("Затемнение", dimValue, dimBar))

        val chipsScroll = HorizontalScrollView(this)
        chipsScroll.isHorizontalScrollBarEnabled = false
        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        ImageFilter.entries.forEachIndexed { i, f ->
            val c = chip(f.title, f == filter, accent) {
                filter = f
                backdrop.filter = f
                filterChips.forEachIndexed { k, ch -> styleChip(ch, k == i, accent) }
            }
            val lp = LinearLayout.LayoutParams(WRAP, dpi(36))
            if (i > 0) lp.leftMargin = dpi(6)
            chips.addView(c, lp)
            filterChips.add(c)
        }
        chipsScroll.addView(chips)
        val clp = LinearLayout.LayoutParams(MATCH, WRAP)
        clp.topMargin = dpi(10)
        sheet.addView(chipsScroll, clp)

        sideSeg = Segmented(this, listOf("Часы слева", "Часы справа"), side, accent) { i ->
            side = i
            placeClock()
        }
        val slp = LinearLayout.LayoutParams(MATCH, WRAP)
        slp.topMargin = dpi(10)
        sheet.addView(sideSeg, slp)

        val lockRow = LinearLayout(this)
        lockRow.orientation = LinearLayout.HORIZONTAL
        lockRow.gravity = Gravity.CENTER_VERTICAL
        val lockText = label(14f, Wired.TEXT, Fonts.ui)
        lockText.text = "И на экран блокировки"
        lockRow.addView(lockText, LinearLayout.LayoutParams(0, WRAP, 1f))
        lockRow.addView(wiredSwitch(alsoLock, accent, "И на экран блокировки") { alsoLock = it })
        val llp = LinearLayout.LayoutParams(MATCH, WRAP)
        llp.topMargin = dpi(4)
        sheet.addView(lockRow, llp)

        val hint = meta("тяни картинку · щипок — масштаб · двойной тап — сброс", 10f)
        hint.setTextColor(Wired.STATIC)
        val hl = LinearLayout.LayoutParams(WRAP, WRAP)
        hl.topMargin = dpi(10)
        sheet.addView(hint, hl)
        root.addView(sheet, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = systemInsets(insets)
            topBar.setPadding(b.left + dpi(16), b.top + dpi(8), b.right + dpi(16), 0)
            sheet.setPadding(b.left + dpi(20), dpi(12), b.right + dpi(20), b.bottom + dpi(18))
            (clockBlock.layoutParams as FrameLayout.LayoutParams).topMargin = b.top + dpi(102)
            placeClock()
            insets
        }
        return root
    }

    private fun sliderBlock(title: String, value: TextView, bar: SeekBar): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val t = label(14f, Wired.TEXT, Fonts.ui)
        t.text = title
        row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(value)
        box.addView(row)
        box.addView(bar, LinearLayout.LayoutParams(MATCH, dpi(32)))
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dpi(12)
        box.layoutParams = lp
        return box
    }

    private fun placeClock() {
        val right = side == Prefs.SIDE_RIGHT
        val lp = clockBlock.layoutParams as FrameLayout.LayoutParams
        lp.gravity = (if (right) Gravity.END else Gravity.START) or Gravity.TOP
        lp.leftMargin = dpi(24)
        lp.rightMargin = dpi(24)
        clockBlock.layoutParams = lp
        clockBlock.removeAllViews()
        textCol.gravity = if (right) Gravity.END else Gravity.START
        val tlp = LinearLayout.LayoutParams(WRAP, WRAP)
        val blp = LinearLayout.LayoutParams(dpi(2), dpi(236))
        if (right) {
            tlp.rightMargin = dpi(16)
            clockBlock.addView(textCol, tlp)
            clockBlock.addView(bar, blp)
        } else {
            tlp.leftMargin = dpi(16)
            clockBlock.addView(bar, blp)
            clockBlock.addView(textCol, tlp)
        }
        clock.alignEnd = right
        backdrop.clockSide = side
        if (::sideSeg.isInitialized) sideSeg.select(side)
    }

    private fun applyTransform() {
        backdrop.setTransform(x, y, scale)
        scaleValue.text = (scale * 100).toInt().toString() + "%"
        scaleBar.progress = ((scale - 0.6f) * 100).toInt().coerceIn(0, 240)
    }

    private fun applyAll() {
        backdrop.filter = filter
        backdrop.dim = dim / 100f
        dimBar.progress = dim
        dimValue.text = "$dim%"
        filterChips.forEachIndexed { k, ch -> styleChip(ch, ImageFilter.entries[k] == filter, accent) }
        applyTransform()
        placeClock()
    }
}
