package com.lawyye.wired

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Настройки": every option of WIRED 2.0, grouped like the design canvas. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var scroll: ScrollView
    private lateinit var col: LinearLayout
    private var accent = Wired.BLUE

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
    }

    override fun onResume() {
        super.onResume()
        val y = scroll.scrollY
        build()
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun changed() {
        prefs.bumpStyle()
    }

    private fun build() {
        accent = prefs.accent
        scroll.removeAllViews()
        col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dpi(20), dpi(12), dpi(20), dpi(40))
        scroll.addView(col)

        // header
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(iconButton(R.drawable.ic_back, "Назад") { finish() })
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        val t = label(26f, Wired.CLOCK, Fonts.uiBold)
        t.text = "Настройки"
        titles.addView(t)
        titles.addView(meta("wired / config · " + BuildConfig.VERSION_NAME))
        val tlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        tlp.leftMargin = dpi(12)
        head.addView(titles, tlp)
        col.addView(head)

        col.addView(previewCard())

        // ------------------------------------------------ screen
        val screen = Group(this, "главный экран")
        screen.add(segRow(R.drawable.ic_home, "Режим экрана", listOf("Картинка", "CRT-окно", "Минимал"), prefs.homeMode) { i ->
            if (i == Prefs.HOME_IMAGE && !prefs.imageFile().exists()) {
                startActivity(Intent(this, ComposerActivity::class.java))
            } else {
                prefs.homeMode = i
                changed()
                if (i == Prefs.HOME_CRT && prefs.crtImages.isEmpty()) startActivity(Intent(this, CrtImagesActivity::class.java))
                build()
            }
        })
        screen.add(settingsRow(R.drawable.ic_image, "Композиция и обои", accent,
            value = ImageFilter.of(prefs.imgFilter).title + " · " + prefs.imgDim + "%") {
            startActivity(Intent(this, ComposerActivity::class.java))
        })
        screen.add(settingsRow(R.drawable.ic_monitor, "Картинки CRT-окна", accent, value = prefs.crtImages.size.toString()) {
            startActivity(Intent(this, CrtImagesActivity::class.java))
        })
        screen.add(segRow(R.drawable.ic_sparkle, "Фон для CRT и минимала", listOf("Провода", "Пустота", "Системные"), prefs.scene) { i ->
            prefs.scene = i
            changed()
        })
        col.addView(screen)

        // ------------------------------------------------ shade
        val sh = Group(this, "шторка")
        sh.add(switchRow(R.drawable.ic_arrow_down, "Своя шторка WIRED по свайпу вниз", prefs.ownShade) { prefs.ownShade = it })
        sh.add(settingsRow(R.drawable.ic_bell, "Доступ к уведомлениям", accent,
            value = if (NotifHub.hasAccess(this)) "есть" else "нет") { NotifHub.openAccess(this) })
        sh.add(settingsRow(R.drawable.ic_sun, "Яркость и поворот из шторки", accent,
            value = if (android.provider.Settings.System.canWrite(this)) "есть" else "нет") {
            AppActions.safeStart(this, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:$packageName")))
        })
        col.addView(sh)
        val shHint = meta("если доступ не включается: Сведения о WIRED → ⋮ → «Разрешить ограниченные настройки». " +
            "Внутри других приложений сверху остаётся шторка Samsung — её не может заменить ни один лаунчер.")
        shHint.setLineSpacing(0f, 1.4f)
        shHint.setPadding(dpi(4), dpi(10), 0, 0)
        col.addView(shHint)

        // ------------------------------------------------ lock screen and recents
        val lock = Group(this, "экран блокировки и недавние")
        lock.add(settingsRow(R.drawable.ic_lock, "WIRED на экран блокировки", accent) { applyWallpaper(LockScreen.LOCK) })
        lock.add(settingsRow(R.drawable.ic_grid, "WIRED фоном в недавних приложениях", accent) { applyWallpaper(LockScreen.SYSTEM) })
        col.addView(lock)
        val lockHint = meta("ставится текущий экран: картинка, CRT-картинка или «Провода», с затемнением, цветом и полосами. " +
            "Часы на блокировке рисует Samsung: зажми палец на экране блокировки → редактор. " +
            "Ряд иконок внизу в недавних — «Предлагаемые приложения», выключаются через ⋮ на том экране.")
        lockHint.setLineSpacing(0f, 1.4f)
        lockHint.setPadding(dpi(4), dpi(10), 0, 0)
        col.addView(lockHint)

        // ------------------------------------------------ look
        val look = Group(this, "внешний вид")
        look.add(settingsRow(R.drawable.ic_palette, "Акцент", accent, trailing = swatches()))
        col.addView(look)

        // ------------------------------------------------ clock
        val clock = Group(this, "часы")
        clock.add(segRow(R.drawable.ic_clock, "Часы на картинке", listOf("Столбиком", "В строку"), prefs.clockStyle) { i ->
            prefs.clockStyle = i
            changed()
        })
        clock.add(segRow(R.drawable.ic_resize, "Сторона часов", listOf("Слева", "Справа"), prefs.clockSide) { i ->
            prefs.clockSide = i
            changed()
        })
        clock.add(switchRow(R.drawable.ic_lines, "Сеть и заряд под часами", prefs.showInfo) { prefs.showInfo = it; changed() })
        clock.add(switchRow(R.drawable.ic_clock, "Секунды (в строке)", prefs.seconds) { prefs.seconds = it; changed() })
        col.addView(clock)

        // ------------------------------------------------ apps
        val apps = Group(this, "приложения")
        apps.add(segRow(R.drawable.ic_grid, "Список приложений", listOf("Сетка", "Список"), prefs.drawerMode) { i ->
            prefs.drawerMode = i
            changed()
        })
        apps.add(segRow(R.drawable.ic_grid, "Колонки в сетке", listOf("4", "5"), if (prefs.columns == 5) 1 else 0) { i ->
            prefs.columns = if (i == 1) 5 else 4
            changed()
        })
        apps.add(segRow(R.drawable.ic_squircle, "Иконки", listOf("Оригинал", "Тон", "Моно"), prefs.iconMode) { i ->
            prefs.iconMode = i
            changed()
        })
        val sizes = listOf(48, 56, 64)
        apps.add(segRow(R.drawable.ic_resize, "Размер иконок", sizes.map { "$it dp" }, sizes.indexOf(prefs.iconSizeDp).coerceAtLeast(1)) { i ->
            prefs.iconSizeDp = sizes[i]
            changed()
        })
        apps.add(switchRow(R.drawable.ic_dock, "Подписи в доке", prefs.dockLabels) { prefs.dockLabels = it; changed() })
        apps.add(switchRow(R.drawable.ic_keyboard, "Клавиатура сразу при открытии", prefs.autoKeyboard) { prefs.autoKeyboard = it })
        apps.add(settingsRow(R.drawable.ic_eye_off, "Скрытые приложения", accent, value = prefs.hidden.size.toString()) { restoreHidden() })
        col.addView(apps)
        val dockHint = meta("добавить в док — удержание на приложении → «В док»")
        dockHint.setPadding(dpi(4), dpi(10), 0, 0)
        col.addView(dockHint)

        // ------------------------------------------------ effects
        val fx = Group(this, "эффекты")
        fx.add(switchRow(R.drawable.ic_scan, "ЭЛТ-линии", prefs.scanlines) { prefs.scanlines = it; changed() })
        fx.add(switchRow(R.drawable.ic_grain, "Зерно", prefs.grain) { prefs.grain = it; changed() })
        fx.add(switchRow(R.drawable.ic_aberration, "Хроматическая аберрация", prefs.aberration) { prefs.aberration = it; changed() })
        fx.add(switchRow(R.drawable.ic_pulse, "Глитч при разблокировке", prefs.glitchUnlock) { prefs.glitchUnlock = it })
        fx.add(switchRow(R.drawable.ic_tap, "Глитч по двойному тапу", prefs.glitchTap) { prefs.glitchTap = it })
        col.addView(fx)

        // ------------------------------------------------ performance
        val perf = Group(this, "производительность")
        val help = meta(profileHelp(prefs.profile))
        help.setLineSpacing(0f, 1.4f)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dpi(16), dpi(16), dpi(16), dpi(16))
        box.addView(Segmented(this, listOf("Экономия", "Баланс", "Визуал"), prefs.profile, accent) { i ->
            prefs.profile = i
            help.text = profileHelp(i)
            changed()
        })
        val hlp = LinearLayout.LayoutParams(MATCH, WRAP)
        hlp.topMargin = dpi(12)
        box.addView(help, hlp)
        perf.add(box)
        col.addView(perf)

        // ------------------------------------------------ system
        val sys = Group(this, "система")
        sys.add(settingsRow(R.drawable.ic_home, "Главный экран по умолчанию", accent,
            value = if (HomeRole.isDefault(this)) "WIRED" else "не выбран") { HomeRole.request(this) })
        sys.add(settingsRow(R.drawable.ic_sparkle, "Системные настройки", accent) {
            AppActions.safeStart(this, Intent(Settings.ACTION_SETTINGS))
        })
        val crashAt = CrashLog.lastCrashTime(this)
        sys.add(settingsRow(R.drawable.ic_pulse, "Журнал сбоев", accent,
            value = if (crashAt == null) "чисто" else android.text.format.DateFormat.format("d MMM, HH:mm", crashAt).toString()) {
            showCrashLog()
        })
        col.addView(sys)
        val lic = meta("шрифты: Space Grotesk, Manrope, IBM Plex Mono — SIL Open Font License 1.1")
        lic.setPadding(dpi(4), dpi(14), 0, 0)
        lic.setLineSpacing(0f, 1.4f)
        col.addView(lic)
    }

    /** The crash log, to copy and send when something breaks — the text names the exact line. */
    private fun showCrashLog() {
        val text = CrashLog.report(this)
        val d = WiredDialog(this, accent).header(null, "Журнал сбоев", "хранится только на телефоне")
        if (text.isEmpty()) {
            d.message("Сбоев не было.").option(R.drawable.ic_check, "Хорошо") {}.show()
            return
        }
        d.message(if (text.length > 4000) text.take(4000) + "\n…" else text)
            .option(R.drawable.ic_check, "Скопировать") {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("WIRED crash log", text))
                toast("Скопировано — пришли этот текст при обращении")
            }
            .option(R.drawable.ic_trash, "Очистить", danger = true) {
                CrashLog.clear(this)
                val y = scroll.scrollY
                build()
                scroll.post { scroll.scrollTo(0, y) }
            }
            .show()
    }

    private fun applyWallpaper(which: Int) {
        toast("Рисую обои…")
        LockScreen.applyCurrent(this, which) { ok -> toast(if (ok) "Готово" else "Не получилось поставить обои") }
    }

    private fun profileHelp(p: Int): String = when (p) {
        Prefs.PROFILE_ECO -> "без эффектов и анимаций · минимум работы для экрана и батареи"
        Prefs.PROFILE_VISUAL -> "всё включено · в CRT-окне бегут полосы и мерцание (30 fps, только пока экран виден)"
        else -> "эффекты — статичный слой · включение монитора и глитч только по событию · анимации на паузе в фоне"
    }

    private fun previewCard(): View {
        val card = FrameLayout(this)
        card.background = rounded(Wired.VOID, 24f, Wired.LINE)
        card.clipToOutline = true
        val bd = HomeBackdropView(this)
        bd.accent = accent
        bd.mode = prefs.homeMode
        bd.scene = prefs.scene
        bd.clockSide = prefs.clockSide
        bd.filter = ImageFilter.of(prefs.imgFilter)
        bd.dim = prefs.imgDim / 100f
        bd.setTransform(prefs.imgX, prefs.imgY, prefs.imgScale)
        bd.placeholder = ""
        card.addView(bd, FrameLayout.LayoutParams(MATCH, MATCH))
        if (prefs.homeMode == Prefs.HOME_IMAGE) {
            ImageStore.decodeAsync(prefs.imageFile(), 1200) { b -> bd.image = b }
        }
        val txt = LinearLayout(this)
        txt.orientation = LinearLayout.VERTICAL
        txt.setPadding(dpi(18), dpi(16), dpi(18), dpi(16))
        txt.addView(meta("layer 07 — personal node", 10f))
        val c = label(56f, Wired.CLOCK, Fonts.clock)
        c.text = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        c.letterSpacing = -0.04f
        c.setShadowLayer(dp(10f), 0f, 0f, Wired.alpha(accent, 0x4D))
        txt.addView(c)
        val d = label(12f, Wired.TEXT_SOFT, Fonts.ui)
        d.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()).lowercase()
        txt.addView(d)
        card.addView(txt, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.TOP))
        val btn = label(13f, 0xFFCFE3FF.toInt(), Fonts.uiSemi)
        btn.text = "Композиция  ›"
        btn.gravity = Gravity.CENTER
        btn.setPadding(dpi(14), 0, dpi(14), 0)
        btn.background = ripple(rounded(0xB3080D17.toInt(), 12f, 0x4D82B9FF), 12f, accent)
        btn.isClickable = true
        btn.setOnClickListener { startActivity(Intent(this, ComposerActivity::class.java)) }
        val blp = FrameLayout.LayoutParams(WRAP, dpi(40), Gravity.END or Gravity.BOTTOM)
        blp.setMargins(0, 0, dpi(14), dpi(12))
        card.addView(btn, blp)
        val lp = LinearLayout.LayoutParams(MATCH, dpi(196))
        lp.topMargin = dpi(20)
        card.layoutParams = lp
        return card
    }

    private fun segRow(icon: Int, title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.addView(settingsRow(icon, title, accent))
        val seg = Segmented(this, options, selected.coerceIn(0, options.size - 1), accent, onPick)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.setMargins(dpi(16), 0, dpi(16), dpi(14))
        box.addView(seg, lp)
        return box
    }

    private fun switchRow(icon: Int, title: String, checked: Boolean, onChange: (Boolean) -> Unit): View =
        settingsRow(icon, title, accent, trailing = wiredSwitch(checked, accent, title, onChange))

    private fun swatches(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        Wired.ACCENTS.forEachIndexed { i, a ->
            val f = FrameLayout(this)
            f.contentDescription = a.name
            f.isClickable = true
            f.isFocusable = true
            if (i == prefs.accentIndex) {
                val ring = View(this)
                val g = GradientDrawable()
                g.shape = GradientDrawable.OVAL
                g.setStroke(dpi(2), a.color)
                ring.background = g
                f.addView(ring, FrameLayout.LayoutParams(dpi(32), dpi(32), Gravity.CENTER))
            }
            val dot = View(this)
            dot.background = oval(a.color)
            f.addView(dot, FrameLayout.LayoutParams(dpi(22), dpi(22), Gravity.CENTER))
            f.setOnClickListener {
                prefs.accentIndex = i
                changed()
                build()
            }
            row.addView(f, LinearLayout.LayoutParams(dpi(38), dpi(44)))
        }
        return row
    }

    private fun restoreHidden() {
        val hidden = prefs.hidden
        if (hidden.isEmpty()) {
            toast("Скрытых приложений нет")
            return
        }
        val byKey = AppRepository.apps.associateBy { it.key }
        val d = WiredDialog(this, accent).header(null, "Скрытые приложения", "нажми, чтобы вернуть")
        for (k in hidden) {
            val name = byKey[k]?.label ?: k.substringBefore('/')
            d.option(R.drawable.ic_eye_off, name) {
                prefs.hidden = prefs.hidden - k
                changed()
                AppRepository.reload(this)
                build()
            }
        }
        d.option(R.drawable.ic_check, "Вернуть все") {
            prefs.hidden = emptySet()
            changed()
            AppRepository.reload(this)
            build()
        }
        d.show()
    }
}
