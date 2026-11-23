package com.lawyye.wired

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class HomeActivity : Activity(), DrawerView.Host, DockView.Host, QuickPanelView.Host {

    private lateinit var prefs: Prefs
    private var accent = Wired.BLUE

    private lateinit var root: FrameLayout
    private lateinit var backdrop: HomeBackdropView
    private lateinit var gestureLayer: View
    private lateinit var content: FrameLayout
    private lateinit var effects: EffectsOverlay
    private lateinit var drawer: DrawerView
    private lateinit var dock: DockView
    private lateinit var crt: CrtWindowView
    private lateinit var shade: QuickPanelView

    private var clock: ClockView? = null
    private var dateLine: TextView? = null
    private var infoLine: TextView? = null

    private lateinit var flingDetector: GestureDetector
    private lateinit var layerDetector: GestureDetector

    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var appliedStyle = -1
    private var loadedImageVersion = -1L
    private var loadedCrt: String? = null
    private var screenWasOff = true
    private var closeDrawerOnStop = false
    private var gestureOnScroller = false
    private var downListAtTop = true
    private var downOnShadeList = false
    private var shadeListAtBottom = true
    private var topInset = 0
    private var downX = 0f
    private var downY = 0f
    private var trackDrag = false
    private var flingHandled = false
    private var batteryPct = -1
    private var charging = false
    private var netLabel = "—"
    private var receiversOn = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val dateFmt by lazy { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()) }
    private val appsListener: () -> Unit = { onAppsChanged() }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        prefs = Prefs(this)
        edgeToEdge()
        buildUi()
        setContentView(root)
        registerScreenOff()
        AppRepository.addListener(appsListener)
        AppRepository.init(this)
        if (AppRepository.loaded) onAppsChanged()
        root.post { maybeWelcome() }
    }

    override fun onResume() {
        super.onResume()
        active = true
        if (appliedStyle != prefs.styleVersion) applyStyle() else loadImages()
        registerLive()
        updateClock()
        updateInfo()
        drawer.setApps(AppRepository.apps, prefs.hidden, prefs.launchCounts())
        crt.onVisible(true)
        if (shade.isOpen) shade.refresh()
        if (screenWasOff) {
            screenWasOff = false
            onUnlock()
        }
    }

    override fun onPause() {
        super.onPause()
        active = false
        unregisterLive()
        crt.onVisible(false)
        handler.removeCallbacks(secondTick)
    }

    override fun onStop() {
        super.onStop()
        if (closeDrawerOnStop) {
            closeDrawerOnStop = false
            drawer.close(false)
        }
        if (shade.isOpen) shade.close(false)
    }

    override fun onDestroy() {
        AppRepository.removeListener(appsListener)
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Throwable) {
            // never registered
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (drawer.isOpen) drawer.close(true)
        if (shade.isOpen) shade.close(true)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // a launcher never leaves on back; back only closes the drawer or the shade
        if (drawer.isOpen) drawer.close(true) else if (shade.isOpen) shade.close(true)
    }

    // ------------------------------------------------------------------ layout

    private fun buildUi() {
        root = FrameLayout(this)
        backdrop = HomeBackdropView(this)
        gestureLayer = View(this)
        content = FrameLayout(this)
        effects = EffectsOverlay(this)
        drawer = DrawerView(this, this)
        dock = DockView(this, this)
        crt = CrtWindowView(this)
        shade = QuickPanelView(this, this)
        crt.setOnClickListener { nextCrtImage() }
        crt.setOnLongClickListener {
            crt.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            startActivity(Intent(this, CrtImagesActivity::class.java))
            true
        }
        for (v in listOf(backdrop, gestureLayer, content, effects, shade, drawer)) {
            root.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        setupGestures()
        root.setOnApplyWindowInsetsListener { _, insets ->
            applyInsets(insets)
            insets
        }
    }

    private fun applyInsets(insets: WindowInsets) {
        val bars = systemInsets(insets)
        val withIme = systemInsets(insets, true)
        topInset = bars.top
        content.setPadding(bars.left + dpi(24), bars.top + dpi(8), bars.right + dpi(24), bars.bottom + dpi(10))
        drawer.setInsets(bars, withIme)
        shade.setInsets(bars.top, bars.bottom, bars.left, bars.right)
    }

    private fun applyStyle() {
        appliedStyle = prefs.styleVersion
        accent = prefs.accent
        val mode = prefs.homeMode

        val showWallpaper = mode != Prefs.HOME_IMAGE && prefs.scene == Prefs.SCENE_SYSTEM
        if (showWallpaper) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (showWallpaper) 0 else Wired.VOID))
        root.setBackgroundColor(if (showWallpaper) 0 else Wired.VOID)

        backdrop.accent = accent
        backdrop.mode = mode
        backdrop.scene = prefs.scene
        backdrop.clockSide = prefs.clockSide
        backdrop.filter = ImageFilter.of(prefs.imgFilter)
        backdrop.dim = prefs.imgDim / 100f
        backdrop.setTransform(prefs.imgX, prefs.imgY, prefs.imgScale)

        effects.configure(prefs.effectsOn, prefs.scanlines, prefs.grain, accent)
        crt.accent = accent
        crt.filter = ImageFilter.of(prefs.crtFilter)
        crt.continuous = prefs.continuousOn
        drawer.applyStyle(prefs)
        shade.applyStyle(accent)
        buildContent()
        bindDock()
        loadImages()
        updateClock()
        updateInfo()
        root.requestApplyInsets()
    }

    private fun buildContent() {
        content.removeAllViews()
        val mode = prefs.homeMode
        val right = mode == Prefs.HOME_IMAGE && prefs.clockSide == Prefs.SIDE_RIGHT
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        content.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        // wordmark
        val mark = LinearLayout(this)
        mark.orientation = LinearLayout.HORIZONTAL
        mark.gravity = Gravity.CENTER_VERTICAL
        val dot = View(this)
        dot.background = oval(accent)
        dot.elevation = 0f
        mark.addView(dot, LinearLayout.LayoutParams(dpi(6), dpi(6)))
        val word = label(13f, Wired.TEXT, Fonts.heading)
        word.text = "WIRED"
        word.letterSpacing = 0.32f
        val wlp = LinearLayout.LayoutParams(WRAP, WRAP)
        wlp.leftMargin = dpi(10)
        mark.addView(word, wlp)
        mark.setPadding(0, dpi(10), dpi(24), dpi(10))
        mark.isClickable = true
        mark.contentDescription = "Шторка WIRED"
        mark.setOnClickListener { openShade() }
        val mlp = LinearLayout.LayoutParams(WRAP, WRAP)
        mlp.topMargin = dpi(4)
        col.addView(mark, mlp)

        // clock block
        val stacked = mode == Prefs.HOME_IMAGE && prefs.clockStyle == Prefs.CLOCK_STACK
        val c = ClockView(this)
        c.stacked = stacked
        c.maxSizeSp = if (stacked) 128f else 104f
        c.accent = accent
        c.alignEnd = right
        c.aberration = prefs.aberration && prefs.effectsOn
        clock = c
        val text = LinearLayout(this)
        text.orientation = LinearLayout.VERTICAL
        text.gravity = if (right) Gravity.END else Gravity.START
        text.addView(c, LinearLayout.LayoutParams(WRAP, WRAP))
        val date = label(15f, Wired.TEXT_SOFT, Fonts.ui)
        val dlp = LinearLayout.LayoutParams(WRAP, WRAP)
        dlp.topMargin = dpi(if (stacked) 16 else 10)
        text.addView(date, dlp)
        dateLine = date
        if (prefs.showInfo) {
            val info = meta("")
            val ilp = LinearLayout.LayoutParams(WRAP, WRAP)
            ilp.topMargin = dpi(6)
            text.addView(info, ilp)
            infoLine = info
        } else {
            infoLine = null
        }
        val block = LinearLayout(this)
        block.orientation = LinearLayout.HORIZONTAL
        if (stacked) {
            val bar = View(this)
            bar.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(accent, Wired.alpha(accent, 0)),
            )
            val tlp = LinearLayout.LayoutParams(WRAP, WRAP)
            if (right) {
                tlp.rightMargin = dpi(16)
                block.addView(text, tlp)
                block.addView(bar, LinearLayout.LayoutParams(dpi(2), dpi(236)))
            } else {
                tlp.leftMargin = dpi(16)
                block.addView(bar, LinearLayout.LayoutParams(dpi(2), dpi(236)))
                block.addView(text, tlp)
            }
        } else {
            block.addView(text)
        }
        val blp = LinearLayout.LayoutParams(WRAP, WRAP)
        blp.gravity = if (right) Gravity.END else Gravity.START
        blp.topMargin = dpi(if (stacked) 64 else 44)
        col.addView(block, blp)

        col.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))

        if (mode == Prefs.HOME_CRT) {
            (crt.parent as? android.view.ViewGroup)?.removeView(crt)
            val clp = LinearLayout.LayoutParams(MATCH, dpi(300))
            clp.leftMargin = dpi(4)
            clp.rightMargin = dpi(4)
            col.addView(crt, clp)
            col.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))
        }

        (dock.parent as? android.view.ViewGroup)?.removeView(dock)
        col.addView(dock, LinearLayout.LayoutParams(MATCH, dpi(if (prefs.dockLabels) 104 else 84)))

        val hint = LinearLayout(this)
        hint.orientation = LinearLayout.VERTICAL
        hint.gravity = Gravity.CENTER_HORIZONTAL
        hint.addView(iconView(R.drawable.ic_up, Wired.TEXT2, 16))
        val ht = meta("swipe up · the wired")
        ht.letterSpacing = 0.04f
        val htlp = LinearLayout.LayoutParams(WRAP, WRAP)
        htlp.topMargin = dpi(4)
        hint.addView(ht, htlp)
        hint.isClickable = true
        hint.setOnClickListener { openDrawer() }
        val hlp = LinearLayout.LayoutParams(MATCH, WRAP)
        hlp.topMargin = dpi(14)
        col.addView(hint, hlp)
    }

    private fun bindDock() {
        val byKey = AppRepository.apps.associateBy { it.key }
        val apps = prefs.dock.mapNotNull { byKey[it] }.filter { it.key !in prefs.hidden }
        dock.bind(apps, 56, prefs.iconMode, prefs.dockLabels, accent)
    }

    private fun loadImages() {
        val dm = resources.displayMetrics
        if (prefs.homeMode == Prefs.HOME_IMAGE) {
            val ver = prefs.imageVersion
            if (ver != loadedImageVersion || backdrop.image == null) {
                loadedImageVersion = ver
                val edge = (maxOf(dm.widthPixels, dm.heightPixels) * 1.25f).toInt()
                ImageStore.decodeAsync(prefs.imageFile(), edge) { b -> if (!isDestroyed) backdrop.image = b }
            }
        } else if (backdrop.image != null) {
            backdrop.image = null
            loadedImageVersion = -1L
        }
        if (prefs.homeMode == Prefs.HOME_CRT) {
            loadCrt(false)
        } else if (crt.image != null) {
            crt.image = null
            loadedCrt = null
        }
    }

    private fun loadCrt(animate: Boolean) {
        val list = prefs.crtImages
        if (list.isEmpty()) {
            crt.image = null
            loadedCrt = null
            if (animate) crt.powerOn(prefs.animationsOn)
            return
        }
        val name = list[prefs.crtIndex.mod(list.size)]
        if (name == loadedCrt && crt.image != null) {
            if (animate) crt.powerOn(prefs.animationsOn)
            return
        }
        loadedCrt = name
        ImageStore.decodeAsync(java.io.File(prefs.crtDir(), name), 900) { b ->
            if (isDestroyed) return@decodeAsync
            crt.image = b
            if (animate) crt.powerOn(prefs.animationsOn)
        }
    }

    private fun nextCrtImage() {
        val list = prefs.crtImages
        if (list.isEmpty()) {
            startActivity(Intent(this, CrtImagesActivity::class.java))
            return
        }
        prefs.crtIndex = (prefs.crtIndex + 1).mod(list.size)
        loadCrt(true)
    }

    /** Screen came back on: the monitor switches on, the clock glitches once. */
    private fun onUnlock() {
        if (prefs.homeMode == Prefs.HOME_CRT) {
            if (prefs.crtRotate && prefs.crtImages.size > 1) {
                prefs.crtIndex = (prefs.crtIndex + 1).mod(prefs.crtImages.size)
            }
            loadCrt(true)
        }
        if (prefs.glitchUnlock && prefs.animationsOn) {
            handler.postDelayed({ glitch(false) }, 260L)
        }
    }

    private fun glitch(strong: Boolean) {
        clock?.burst(strong)
        effects.burst(strong)
    }

    private fun onAppsChanged() {
        drawer.setApps(AppRepository.apps, prefs.hidden, prefs.launchCounts())
        bindDock()
        IconCache.prewarm(this, AppRepository.apps, dpi(prefs.iconSizeDp))
    }

    // ------------------------------------------------------------------ live data

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            readBattery(intent)
            updateInfo()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenWasOff = true
        }
    }

    private val secondTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun register(r: BroadcastReceiver, f: IntentFilter): Intent? = try {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(r, f)
    } catch (e: Throwable) {
        null
    }

    private fun registerScreenOff() {
        register(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    private fun registerLive() {
        if (receiversOn) return
        receiversOn = true
        val tf = IntentFilter()
        tf.addAction(Intent.ACTION_TIME_TICK)
        tf.addAction(Intent.ACTION_TIME_CHANGED)
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED)
        register(timeReceiver, tf)
        register(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { readBattery(it) }
        if (prefs.seconds && !(prefs.homeMode == Prefs.HOME_IMAGE && prefs.clockStyle == Prefs.CLOCK_STACK)) {
            handler.post(secondTick)
        }
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            if (cm != null) {
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        val l = labelOf(caps)
                        handler.post {
                            netLabel = l
                            updateInfo()
                        }
                    }

                    override fun onLost(network: Network) {
                        handler.post {
                            netLabel = "offline"
                            updateInfo()
                        }
                    }
                }
                netLabel = cm.activeNetwork?.let { n -> cm.getNetworkCapabilities(n)?.let { labelOf(it) } } ?: "offline"
                cm.registerDefaultNetworkCallback(cb)
                netCallback = cb
            }
        } catch (e: Throwable) {
            netCallback = null
        }
    }

    private fun unregisterLive() {
        if (!receiversOn) return
        receiversOn = false
        for (r in listOf(timeReceiver, batteryReceiver)) {
            try {
                unregisterReceiver(r)
            } catch (e: Throwable) {
                // already gone
            }
        }
        netCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            } catch (e: Throwable) {
                // already gone
            }
        }
        netCallback = null
    }

    private fun labelOf(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wi-fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "online"
    }

    private fun readBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun updateClock() {
        val c = clock ?: return
        val cal = Calendar.getInstance()
        val is24 = android.text.format.DateFormat.is24HourFormat(this)
        var h = cal.get(Calendar.HOUR_OF_DAY)
        if (!is24) {
            h %= 12
            if (h == 0) h = 12
        }
        val hh = String.format(Locale.US, "%02d", h)
        val mm = String.format(Locale.US, "%02d", cal.get(Calendar.MINUTE))
        val ss = if (prefs.seconds) String.format(Locale.US, ":%02d", cal.get(Calendar.SECOND)) else ""
        c.setTime(hh, mm, ss)
        dateLine?.text = dateFmt.format(Date()).lowercase()
        if (::shade.isInitialized && shade.isOpen) shade.updateTime()
    }

    private fun updateInfo() {
        val t = infoLine ?: return
        val bat = if (batteryPct < 0) "" else " · " + batteryPct + "%" + if (charging) " +" else ""
        t.text = netLabel + bat
    }

    // ------------------------------------------------------------------ gestures

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        flingDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val dy = e2.rawY - start.rawY
                val dx = e2.rawX - start.rawX
                if (abs(dy) < dp(56f) || abs(dy) < abs(dx) * 1.3f || abs(velocityY) < 300f) return false
                if (drawer.isOpen) {
                    if (dy > 0 && downListAtTop) {
                        flingHandled = true
                        drawer.close(true)
                        return true
                    }
                    return false
                }
                if (shade.isOpen) {
                    if (dy < 0 && (!downOnShadeList || shadeListAtBottom)) {
                        flingHandled = true
                        shade.close(true)
                        return true
                    }
                    return false
                }
                flingHandled = true
                if (dy < 0) openDrawer() else openShade()
                return true
            }
        })

        layerDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onLongPress(e: MotionEvent) {
                gestureLayer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showHomeMenu()
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (prefs.glitchTap && prefs.animationsOn) glitch(true)
                return true
            }
        })

        gestureLayer.setOnTouchListener { _, ev -> layerDetector.onTouchEvent(ev) }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = ev.rawX
            downY = ev.rawY
            flingHandled = false
            trackDrag = !drawer.isOpen && !shade.isOpen && ev.rawY > topInset + dp(8f)
            gestureOnScroller = drawer.isOpen && drawer.isTouchOnScroller(ev.rawX, ev.rawY)
            downListAtTop = drawer.isAtTop()
            downOnShadeList = shade.isOpen && shade.isTouchOnList(ev.rawX, ev.rawY)
            shadeListAtBottom = shade.listAtBottom()
        }
        if (!gestureOnScroller) flingDetector.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP && trackDrag && !flingHandled) {
            // a slow, deliberate drag also opens the drawer (up) or the shade (down)
            val dx = ev.rawX - downX
            val dy = ev.rawY - downY
            if (abs(dy) > dp(90f) && abs(dy) > abs(dx) * 1.5f) {
                val handled = super.dispatchTouchEvent(ev)
                if (dy < 0) openDrawer() else openShade()
                return handled
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun openShade() {
        if (drawer.isOpen) return
        if (prefs.ownShade) shade.open() else expandNotifications()
    }

    @SuppressLint("WrongConstant", "PrivateApi")
    private fun expandNotifications() {
        try {
            val sb = getSystemService("statusbar") ?: return
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        } catch (e: Throwable) {
            // not allowed on this device; ignore
        }
    }

    // ------------------------------------------------------------------ menus

    private fun showHomeMenu() {
        val mode = prefs.homeMode
        WiredDialog(this, accent)
            .option(R.drawable.ic_image, "Композиция и обои") { openComposer() }
            .option(R.drawable.ic_squircle, "Экран: изображение", checked = mode == Prefs.HOME_IMAGE) { setMode(Prefs.HOME_IMAGE) }
            .option(R.drawable.ic_monitor, "Экран: CRT-окно", checked = mode == Prefs.HOME_CRT) { setMode(Prefs.HOME_CRT) }
            .option(R.drawable.ic_lines, "Экран: минимал", checked = mode == Prefs.HOME_MINIMAL) { setMode(Prefs.HOME_MINIMAL) }
            .option(R.drawable.ic_lock, "WIRED на блокировку и в недавние") { applySystemWallpaper(LockScreen.LOCK or LockScreen.SYSTEM) }
            .option(R.drawable.ic_gear, "Настройки WIRED") { openSettings() }
            .option(R.drawable.ic_sparkle, "Системные настройки") { AppActions.safeStart(this, Intent(Settings.ACTION_SETTINGS)) }
            .show()
    }

    private fun setMode(mode: Int) {
        if (mode == Prefs.HOME_IMAGE && !prefs.imageFile().exists()) {
            openComposer()
            return
        }
        prefs.homeMode = mode
        prefs.bumpStyle()
        applyStyle()
        if (mode == Prefs.HOME_CRT) {
            if (prefs.crtImages.isEmpty()) startActivity(Intent(this, CrtImagesActivity::class.java)) else loadCrt(true)
        }
    }

    private fun openComposer() {
        if (drawer.isOpen) closeDrawerOnStop = true
        startActivity(Intent(this, ComposerActivity::class.java))
    }

    private fun maybeWelcome() {
        if (prefs.welcomeShown) {
            maybeShadeHint()
            return
        }
        prefs.welcomeShown = true
        val d = WiredDialog(this, accent)
            .header(null, "WIRED 2.0", "layer 07 — personal node")
            .message(
                "Свайп вверх — все приложения.\n" +
                    "Свайп вниз — уведомления.\n" +
                    "Удержание на пустом месте — режим экрана и обои.\n" +
                    "Удержание на приложении — в док, скрыть, удалить."
            )
        if (!HomeRole.isDefault(this)) d.option(R.drawable.ic_home, "Сделать главным экраном") { HomeRole.request(this) }
        d.option(R.drawable.ic_image, "Поставить свою картинку") { openComposer() }
        d.option(R.drawable.ic_check, "Начать") { prefs.shadeHintShown = true }
        d.show()
    }

    private fun maybeShadeHint() {
        if (prefs.shadeHintShown) return
        prefs.shadeHintShown = true
        WiredDialog(this, accent)
            .header(null, "Шторка WIRED", "новое")
            .message(
                "Свайп вниз из середины экрана или тап по «WIRED» вверху.\n\n" +
                    "Свайп от самого верхнего края — это шторка Samsung: этот жест система забирает себе раньше любого приложения."
            )
            .option(R.drawable.ic_arrow_down, "Открыть шторку") { openShade() }
            .option(R.drawable.ic_check, "Понятно") {}
            .show()
    }

    // ------------------------------------------------------------------ hosts

    override fun openDrawer() {
        if (!drawer.isOpen) drawer.open(prefs.autoKeyboard)
    }

    override fun launchApp(app: AppEntry, from: View) {
        if (AppActions.launch(this, app, from) && drawer.isOpen) closeDrawerOnStop = true
    }

    override fun appMenu(app: AppEntry) {
        val dockKeys = prefs.dock.toMutableList()
        val idx = dockKeys.indexOf(app.key)
        val filter = iconFilter(prefs.iconMode, accent)
        IconCache.load(this, app, dpi(44)) { icon ->
            val d = WiredDialog(this, accent).header(icon, app.label, AppEntry.categoryMeta(app.category).ifEmpty { app.packageName }, filter)
            if (idx >= 0) {
                d.option(R.drawable.ic_pin, "Убрать из дока") {
                    dockKeys.removeAt(idx)
                    prefs.dock = dockKeys
                    bindDock()
                }
                if (idx > 0) {
                    d.option(R.drawable.ic_back, "Сдвинуть левее") {
                        dockKeys.removeAt(idx)
                        dockKeys.add(idx - 1, app.key)
                        prefs.dock = dockKeys
                        bindDock()
                    }
                }
            } else {
                d.option(R.drawable.ic_pin, "В док") {
                    if (dockKeys.size >= Prefs.MAX_DOCK) {
                        toast("В доке максимум " + Prefs.MAX_DOCK + " приложений")
                    } else {
                        dockKeys.add(app.key)
                        prefs.dock = dockKeys
                        bindDock()
                    }
                }
            }
            d.option(R.drawable.ic_eye_off, "Скрыть") {
                prefs.hidden = prefs.hidden + app.key
                prefs.dock = prefs.dock - app.key
                onAppsChanged()
                toast("Скрыто. Вернуть — в настройках")
            }
            d.option(R.drawable.ic_info, "О приложении") { AppActions.info(this, app) }
            d.option(R.drawable.ic_trash, "Удалить", danger = true) { AppActions.uninstall(this, app) }
            d.show()
        }
    }

    override fun openSettings() {
        if (drawer.isOpen) closeDrawerOnStop = true
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun requestClose() = drawer.close(true)

    override fun webSearch(query: String) {
        AppActions.webSearch(this, query)
        if (drawer.isOpen) closeDrawerOnStop = true
    }

    override fun mapsSearch(query: String) {
        AppActions.mapsSearch(this, query)
        if (drawer.isOpen) closeDrawerOnStop = true
    }

    override fun storeSearch(query: String) {
        AppActions.storeSearch(this, query)
        if (drawer.isOpen) closeDrawerOnStop = true
    }

    override fun drawerModeChanged(mode: Int) {
        prefs.drawerMode = mode
    }

    override fun systemShade() {
        shade.close(false)
        expandNotifications()
    }

    override fun closeShade() = shade.close(true)

    private fun applySystemWallpaper(which: Int) {
        toast("Рисую обои…")
        LockScreen.applyCurrent(this, which) { ok ->
            toast(if (ok) "Готово: экран блокировки и фон недавних обновлены" else "Не получилось поставить обои")
        }
    }
}
