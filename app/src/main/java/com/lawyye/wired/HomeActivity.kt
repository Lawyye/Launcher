package com.lawyye.wired

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.random.Random

class HomeActivity : Activity(), DrawerView.Host {

    private lateinit var prefs: Prefs
    private var accent = Palette.TEXT
    private var userHost = "lain@navi"
    private var showSeconds = true

    private lateinit var root: FrameLayout
    private lateinit var bgImage: ImageView
    private lateinit var dimView: View
    private lateinit var stream: DataStreamView
    private lateinit var hud: HudFrameView
    private lateinit var gestureLayer: View
    private lateinit var home: LinearLayout
    private lateinit var drawer: DrawerView
    private lateinit var crt: CrtOverlayView

    private lateinit var statusRight: TextView
    private lateinit var clock: GlitchTextView
    private lateinit var dateLine: TextView
    private lateinit var pinnedBox: LinearLayout
    private lateinit var prompt: TextView
    private val info = LinkedHashMap<String, TextView>()

    private lateinit var flingDetector: GestureDetector
    private lateinit var layerDetector: GestureDetector

    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var appliedStyle = -1
    private var loadedWallVersion = -1L
    private var tickCount = 0
    private var cursorOn = true
    private var batteryPct = -1
    private var charging = false
    private var batteryRegistered = false
    private var closeDrawerOnStop = false
    private var gestureOnScroller = false
    private var downListAtTop = true

    private val timeFmt24 = SimpleDateFormat("HH:mm", Locale.US)
    private val timeFmt12 = SimpleDateFormat("hh:mm", Locale.US)
    private val secFmt = SimpleDateFormat("ss", Locale.US)
    private val ampmFmt = SimpleDateFormat("a", Locale.US)
    private val dateFmt = SimpleDateFormat("EEE dd.MM.yyyy", Locale.ENGLISH)

    private val appsListener: () -> Unit = { onAppsChanged() }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        edgeToEdge()
        buildUi()
        setContentView(root)
        AppRepository.addListener(appsListener)
        AppRepository.init(this)
        root.post { maybeWelcome() }
    }

    override fun onResume() {
        super.onResume()
        active = true
        if (appliedStyle != prefs.styleVersion) {
            applyStyle()
        } else if (prefs.bgMode == Prefs.BG_IMAGE) {
            loadWallpaper()
        }
        registerBattery()
        handler.removeCallbacks(tick)
        tick.run()
        scheduleBurst()
        if (prefs.dataStream && !drawer.isOpen) stream.resume()
    }

    override fun onPause() {
        super.onPause()
        active = false
        handler.removeCallbacks(tick)
        handler.removeCallbacks(burstRunnable)
        unregisterBattery()
        stream.pause()
    }

    override fun onStop() {
        super.onStop()
        if (closeDrawerOnStop) {
            closeDrawerOnStop = false
            hideDrawer(false)
        }
    }

    override fun onDestroy() {
        AppRepository.removeListener(appsListener)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Home pressed while we are already in front.
        if (drawer.isOpen) hideDrawer(true) else if (active) burst(false)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // A launcher never leaves on back; back only closes the drawer.
        if (drawer.isOpen) hideDrawer(true)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == WallpaperStore.REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            toast("DECODING IMAGE...")
            WallpaperStore.importImage(this, uri) { ok ->
                if (ok) {
                    prefs.bgMode = Prefs.BG_IMAGE
                    prefs.wallVersion = System.currentTimeMillis()
                    prefs.bumpStyle()
                    if (active) applyStyle()
                    toast("WALLPAPER LOADED")
                } else {
                    toast("READ ERROR")
                }
            }
        }
    }

    // ------------------------------------------------------------------ layout

    private fun buildUi() {
        root = FrameLayout(this)
        bgImage = ImageView(this)
        bgImage.scaleType = ImageView.ScaleType.CENTER_CROP
        bgImage.visibility = View.GONE
        dimView = View(this)
        stream = DataStreamView(this)
        hud = HudFrameView(this)
        gestureLayer = View(this)
        home = LinearLayout(this)
        home.orientation = LinearLayout.VERTICAL
        drawer = DrawerView(this, this)
        crt = CrtOverlayView(this)
        val layers = listOf(bgImage, dimView, stream, hud, gestureLayer, home, drawer, crt)
        for (v in layers) root.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        setupGestures()
        root.setOnApplyWindowInsetsListener { _, insets ->
            applyInsets(insets)
            insets
        }
    }

    private fun applyInsets(insets: WindowInsets) {
        val bars = systemInsets(insets)
        val withIme = systemInsets(insets, true)
        home.setPadding(dpi(22) + bars.left, dpi(6) + bars.top, dpi(22) + bars.right, dpi(10) + bars.bottom)
        hud.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        drawer.setInsets(bars, withIme)
    }

    private fun spacer(weight: Float) {
        home.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, weight))
    }

    private fun buildHome() {
        home.removeAllViews()
        info.clear()

        // top status line
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val statusLeft = monoText(10f, Palette.DIM)
        statusLeft.text = "NAVI://HOME   LAYER:01"
        statusRight = monoText(10f, accent)
        statusRight.text = "● WIRED"
        top.addView(statusLeft, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(statusRight)
        val topLp = LinearLayout.LayoutParams(MATCH, WRAP)
        topLp.topMargin = dpi(10)
        home.addView(top, topLp)

        spacer(1f)

        // clock
        val tag = monoText(10f, Palette.DIM)
        tag.text = "[ SYS.CLOCK ]"
        tag.gravity = Gravity.CENTER
        home.addView(tag, LinearLayout.LayoutParams(MATCH, WRAP))
        clock = GlitchTextView(this)
        home.addView(clock, LinearLayout.LayoutParams(MATCH, WRAP))
        dateLine = monoText(11f, Palette.TEXT)
        dateLine.gravity = Gravity.CENTER
        dateLine.letterSpacing = 0.16f
        home.addView(dateLine, LinearLayout.LayoutParams(MATCH, WRAP))

        // outlined pill label
        val pill = monoText(11f, Palette.TEXT)
        pill.setTypeface(Palette.mono, Typeface.BOLD_ITALIC)
        pill.text = "the wired"
        val pillBg = GradientDrawable()
        pillBg.cornerRadius = dp(50f)
        pillBg.setStroke(dpi(1), Palette.withAlpha(Palette.TEXT, 210))
        pill.background = pillBg
        pill.setPadding(dpi(14), dpi(4), dpi(14), dpi(5))
        val pillLp = LinearLayout.LayoutParams(WRAP, WRAP)
        pillLp.gravity = Gravity.CENTER_HORIZONTAL
        pillLp.topMargin = dpi(20)
        pillLp.bottomMargin = dpi(14)
        home.addView(pill, pillLp)

        // credits-style system readout
        for (key in arrayOf("BATTERY", "MEMORY", "STORAGE", "UPTIME", "NETWORK")) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val l = monoText(10f, Palette.DIM)
            l.text = key
            l.gravity = Gravity.END
            l.letterSpacing = 0.14f
            val v = monoText(10f, Palette.TEXT)
            v.text = "--"
            v.letterSpacing = 0.08f
            v.maxLines = 1
            row.addView(l, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(View(this), LinearLayout.LayoutParams(dpi(16), 1))
            row.addView(v, LinearLayout.LayoutParams(0, WRAP, 1.3f))
            val rowLp = LinearLayout.LayoutParams(MATCH, WRAP)
            rowLp.topMargin = dpi(4)
            home.addView(row, rowLp)
            info[key] = v
        }

        spacer(1f)

        // pinned apps
        val pinHead = monoText(10f, Palette.DIM)
        pinHead.text = "// PINNED NODES"
        val pinHeadLp = LinearLayout.LayoutParams(MATCH, WRAP)
        pinHeadLp.bottomMargin = dpi(4)
        home.addView(pinHead, pinHeadLp)
        pinnedBox = LinearLayout(this)
        pinnedBox.orientation = LinearLayout.VERTICAL
        home.addView(pinnedBox, LinearLayout.LayoutParams(MATCH, WRAP))

        // terminal prompt
        val promptRow = LinearLayout(this)
        promptRow.orientation = LinearLayout.HORIZONTAL
        promptRow.gravity = Gravity.CENTER_VERTICAL
        prompt = monoText(13f, Palette.TEXT)
        prompt.setPadding(0, dpi(12), 0, dpi(12))
        prompt.setOnClickListener { openDrawer(true) }
        val cfg = monoText(11f, Palette.DIM)
        cfg.text = "[cfg]"
        cfg.setPadding(dpi(12), dpi(12), 0, dpi(12))
        cfg.setOnClickListener { openSettings() }
        promptRow.addView(prompt, LinearLayout.LayoutParams(0, WRAP, 1f))
        promptRow.addView(cfg)
        val promptLp = LinearLayout.LayoutParams(MATCH, WRAP)
        promptLp.topMargin = dpi(8)
        home.addView(promptRow, promptLp)

        val hint = monoText(9f, Palette.withAlpha(Palette.DIM, 190))
        hint.text = "▲ swipe up · access the wired"
        hint.gravity = Gravity.CENTER
        home.addView(hint, LinearLayout.LayoutParams(MATCH, WRAP))

        renderPinned()
        updateClock()
        updateInfo()
        updatePrompt()
    }

    private fun applyStyle() {
        appliedStyle = prefs.styleVersion
        accent = Palette.accent(this)
        showSeconds = prefs.seconds
        userHost = prefs.userHost
        when (prefs.bgMode) {
            Prefs.BG_SYSTEM -> {
                root.background = null
                bgImage.visibility = View.GONE
            }
            Prefs.BG_IMAGE -> {
                root.setBackgroundColor(Palette.VOID)
                loadWallpaper()
            }
            else -> {
                root.setBackgroundColor(Palette.VOID)
                bgImage.visibility = View.GONE
            }
        }
        dimView.setBackgroundColor(Color.argb(prefs.dim * 255 / 100, 0, 0, 0))
        crt.configure(prefs.scanlines, accent)
        stream.color = accent
        stream.visibility = if (prefs.dataStream) View.VISIBLE else View.GONE
        if (!prefs.dataStream) stream.pause()
        hud.color = Palette.TEXT
        hud.invalidate()
        buildHome()
        drawer.applyStyle(accent, prefs.iconMode, userHost)
        drawer.setApps(AppRepository.apps, prefs.hidden)
        root.requestApplyInsets()
    }

    private fun loadWallpaper() {
        val f = WallpaperStore.file(this)
        if (!f.exists()) {
            bgImage.visibility = View.GONE
            return
        }
        bgImage.visibility = View.VISIBLE
        if (prefs.duotone) bgImage.colorFilter = Palette.duotone(accent, 0f, 1.2f) else bgImage.clearColorFilter()
        val ver = prefs.wallVersion
        if (ver == loadedWallVersion && bgImage.drawable != null) return
        loadedWallVersion = ver
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        Thread {
            val bmp = WallpaperStore.load(f, w, h)
            runOnUiThread { if (!isDestroyed) bgImage.setImageBitmap(bmp) }
        }.start()
    }

    // ------------------------------------------------------------------ pinned apps

    private fun renderPinned() {
        if (!::pinnedBox.isInitialized) return
        pinnedBox.removeAllViews()
        val apps = AppRepository.apps
        val byKey = apps.associateBy { it.key }
        val pinned = prefs.pinned.mapNotNull { byKey[it] }.take(Prefs.MAX_PINNED)
        if (pinned.isEmpty()) {
            val tv = monoText(10f, Palette.DIM)
            tv.text = if (apps.isEmpty()) "scanning the wired..." else "no nodes pinned.\nhold any app in the wired to pin it here."
            tv.setLineSpacing(0f, 1.35f)
            pinnedBox.addView(tv)
            return
        }
        val linkStyle = prefs.nodeStyle == Prefs.NODE_LINK
        pinned.forEachIndexed { i, app ->
            val tv = TextView(this)
            if (linkStyle) {
                tv.typeface = Typeface.SERIF
                tv.textSize = 19f
                val s = "CLICK to " + app.label.lowercase() + "!"
                val sb = SpannableStringBuilder(s)
                sb.setSpan(UnderlineSpan(), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                tv.text = sb
                tv.setTextColor(Palette.LINK)
                tv.setPadding(0, dpi(3), 0, dpi(3))
            } else {
                tv.typeface = Palette.mono
                tv.textSize = 15f
                tv.letterSpacing = 0.06f
                val sb = SpannableStringBuilder()
                sb.append("▸ ", ForegroundColorSpan(accent), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append(String.format(Locale.US, "%02d  ", i + 1), ForegroundColorSpan(Palette.DIM), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append(app.label.uppercase())
                tv.text = sb
                tv.setTextColor(Palette.TEXT)
                tv.setPadding(0, dpi(8), 0, dpi(8))
            }
            tv.maxLines = 1
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.background = pressBg(accent)
            tv.setOnClickListener { launchApp(app, tv) }
            tv.setOnLongClickListener {
                tv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                appMenu(app)
                true
            }
            pinnedBox.addView(tv, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun onAppsChanged() {
        drawer.setApps(AppRepository.apps, prefs.hidden)
        renderPinned()
    }

    // ------------------------------------------------------------------ live readouts

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            cursorOn = !cursorOn
            updatePrompt()
            if (tickCount % 10 == 0) updateInfo()
            tickCount++
            handler.postDelayed(this, 500L - System.currentTimeMillis() % 500L)
        }
    }

    private fun updateClock() {
        if (!::clock.isInitialized) return
        val now = Date()
        val tz = TimeZone.getDefault()
        val is24 = DateFormat.is24HourFormat(this)
        val f = if (is24) timeFmt24 else timeFmt12
        f.timeZone = tz
        secFmt.timeZone = tz
        dateFmt.timeZone = tz
        ampmFmt.timeZone = tz
        clock.setText(f.format(now), if (showSeconds) ":" + secFmt.format(now) else "")
        val ampm = if (is24) "" else "  " + ampmFmt.format(now)
        val zone = tz.getDisplayName(tz.inDaylightTime(now), TimeZone.SHORT, Locale.ENGLISH)
        dateLine.text = dateFmt.format(now).uppercase() + ampm + "  //  " + zone
    }

    private fun bar(pct: Int): String {
        val p = pct.coerceIn(0, 100)
        val n = (p + 5) / 10
        return "[" + "|".repeat(n) + "·".repeat(10 - n) + "] " + String.format(Locale.US, "%3d%%", p)
    }

    private fun updateInfo() {
        if (info.isEmpty()) return
        info["BATTERY"]?.text = if (batteryPct < 0) "--" else bar(batteryPct) + if (charging) " +" else ""
        try {
            val am = getSystemService(ActivityManager::class.java)
            if (am != null) {
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                if (mi.totalMem > 0) info["MEMORY"]?.text = bar(((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt())
            }
        } catch (e: Throwable) {
            info["MEMORY"]?.text = "--"
        }
        try {
            val st = StatFs(Environment.getDataDirectory().path)
            if (st.totalBytes > 0) info["STORAGE"]?.text = bar(((st.totalBytes - st.availableBytes) * 100 / st.totalBytes).toInt())
        } catch (e: Throwable) {
            info["STORAGE"]?.text = "--"
        }
        val up = SystemClock.elapsedRealtime() / 1000L
        info["UPTIME"]?.text = String.format(Locale.US, "%dD %02dH %02dM", up / 86400L, (up % 86400L) / 3600L, (up % 3600L) / 60L)
        val net = networkLabel()
        info["NETWORK"]?.text = net
        if (::statusRight.isInitialized) {
            val offline = net == "OFFLINE"
            statusRight.text = if (offline) "○ OFFLINE" else "● WIRED"
            statusRight.setTextColor(if (offline) Palette.RED else accent)
        }
    }

    private fun networkLabel(): String {
        return try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return "UNKNOWN"
            val n = cm.activeNetwork ?: return "OFFLINE"
            val caps = cm.getNetworkCapabilities(n) ?: return "OFFLINE"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "ONLINE"
            }
        } catch (e: Throwable) {
            "UNKNOWN"
        }
    }

    private fun updatePrompt() {
        if (!::prompt.isInitialized) return
        val sb = SpannableStringBuilder()
        sb.append(userHost, ForegroundColorSpan(accent), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(":~\$ ", ForegroundColorSpan(Palette.DIM), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(if (cursorOn) "█" else " ", ForegroundColorSpan(Palette.TEXT), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        prompt.text = sb
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            readBattery(intent)
            updateInfo()
        }
    }

    private fun readBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerBattery() {
        if (batteryRegistered) return
        try {
            val sticky = registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryRegistered = true
            if (sticky != null) readBattery(sticky)
        } catch (e: Throwable) {
            batteryRegistered = false
        }
    }

    private fun unregisterBattery() {
        if (!batteryRegistered) return
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Throwable) {
            // already gone
        }
        batteryRegistered = false
    }

    // ------------------------------------------------------------------ glitch

    private val burstRunnable = Runnable {
        burst(false)
        scheduleBurst()
    }

    private fun scheduleBurst() {
        handler.removeCallbacks(burstRunnable)
        val lvl = prefs.glitch
        if (lvl == 0 || !active) return
        val delay = if (lvl == 1) Random.nextLong(5000L, 12000L) else Random.nextLong(1800L, 5000L)
        handler.postDelayed(burstRunnable, delay)
    }

    private fun burst(strong: Boolean) {
        if (!strong && prefs.glitch == 0) return
        crt.burst(strong)
        if (::clock.isInitialized) clock.burst(strong)
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
                        hideDrawer(true)
                        return true
                    }
                    return false
                }
                if (dy < 0) openDrawer(false) else expandNotifications()
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
                burst(true)
                return true
            }
        })

        gestureLayer.setOnTouchListener { _, ev -> layerDetector.onTouchEvent(ev) }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            gestureOnScroller = drawer.isOpen && drawer.isTouchOnScroller(ev.rawX, ev.rawY)
            downListAtTop = drawer.isAtTop()
        }
        if (!gestureOnScroller) flingDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
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

    private fun openDrawer(keyboard: Boolean) {
        if (drawer.isOpen) return
        drawer.open(keyboard || prefs.autoKeyboard)
        stream.pause()
        burst(false)
    }

    private fun hideDrawer(animated: Boolean) {
        if (!drawer.isOpen) return
        drawer.close(animated)
        if (active && prefs.dataStream) stream.resume()
    }

    // ------------------------------------------------------------------ menus

    private fun showHomeMenu() {
        WinDialog(this, "DESKTOP.SYS", accent)
            .option("ACCESS THE WIRED") { openDrawer(true) }
            .option("CHANGE WALLPAPER") { pickWallpaper() }
            .option("WIRED SETTINGS") { openSettings() }
            .option("SYSTEM SETTINGS") { AppActions.safeStart(this, Intent(Settings.ACTION_SETTINGS)) }
            .show()
    }

    private fun pickWallpaper() {
        try {
            startActivityForResult(WallpaperStore.pickIntent(), WallpaperStore.REQ_PICK)
        } catch (e: Throwable) {
            toast("NO FILE PICKER FOUND")
        }
    }

    private fun maybeWelcome() {
        if (prefs.welcomeShown) return
        prefs.welcomeShown = true
        val d = WinDialog(this, "WELCOME.EXE", accent).message(
            "connection established.\n\n" +
                "swipe up ....... all apps\n" +
                "swipe down ..... notifications\n" +
                "hold empty ..... menu\n" +
                "hold an app .... pin / hide / info\n" +
                "double tap ..... glitch"
        )
        if (!HomeRole.isDefault(this)) d.option("SET AS DEFAULT HOME") { HomeRole.request(this) }
        d.option("PICK WALLPAPER") { pickWallpaper() }
        d.option("ENTER") {}
        d.show()
    }

    // ------------------------------------------------------------------ DrawerView.Host

    override fun launchApp(app: AppEntry, from: View) {
        if (AppActions.launch(this, app, from) && drawer.isOpen) closeDrawerOnStop = true
    }

    override fun appMenu(app: AppEntry) {
        val pinned = prefs.pinned.toMutableList()
        val idx = pinned.indexOf(app.key)
        val d = WinDialog(this, "NODE://" + app.label.uppercase(), accent)
            .message(app.packageName + if (app.isWork) "  [work]" else "")
        if (idx >= 0) {
            d.option("UNPIN FROM HOME") {
                pinned.removeAt(idx)
                prefs.pinned = pinned
                renderPinned()
            }
            if (idx > 0) {
                d.option("MOVE UP") {
                    pinned.removeAt(idx)
                    pinned.add(idx - 1, app.key)
                    prefs.pinned = pinned
                    renderPinned()
                }
            }
        } else {
            d.option("PIN TO HOME") {
                if (pinned.size >= Prefs.MAX_PINNED) {
                    toast("MAX " + Prefs.MAX_PINNED + " NODES. UNPIN ONE FIRST.")
                } else {
                    pinned.add(app.key)
                    prefs.pinned = pinned
                    renderPinned()
                    toast("NODE PINNED")
                }
            }
        }
        d.option("APP INFO") { AppActions.info(this, app) }
        d.option("UNINSTALL") { AppActions.uninstall(this, app) }
        d.option("HIDE FROM THE WIRED") {
            prefs.hidden = prefs.hidden + app.key
            prefs.pinned = prefs.pinned - app.key
            onAppsChanged()
            toast("NODE HIDDEN. RESTORE IN [cfg].")
        }
        d.show()
    }

    override fun openSettings() {
        if (drawer.isOpen) closeDrawerOnStop = true
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun requestClose() {
        hideDrawer(true)
    }

    override fun webSearch(query: String) {
        AppActions.webSearch(this, query)
        if (drawer.isOpen) closeDrawerOnStop = true
    }
}
