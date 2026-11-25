package com.lawyye.wired

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * The WIRED shade over every app. Android lets no app replace Samsung's shade, but an
 * accessibility service may lay a window over the status bar: a strip as tall as the status bar
 * catches the swipe down and opens the WIRED shade in its own overlay window.
 *
 * - Samsung's shade stays one tap away (the arrow in the WIRED shade), or keeps half of the top
 *   edge if the user picks a zone in settings.
 * - Never on the lock screen: the strip is removed while the phone is locked, so locked phones
 *   show neither notifications nor toggles.
 * - Costs nothing while closed: no accessibility events are requested until the shade opens.
 */
class WiredShadeService : AccessibilityService(), QuickPanelView.Host {

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private var strip: View? = null
    private var stripShown = false
    /** The shade window while it is on screen. */
    private var shadeWindow: ShadeWindow? = null

    /**
     * The same window kept between swipes: building the panel (about a hundred views, plus a query
     * to the camera service for the flashlight) on every swipe made the top shade slow to appear.
     * Dropped on a configuration change (rotation, font size, theme), when its sizes go stale.
     */
    private var cachedWindow: ShadeWindow? = null
    private var openedAt = 0L
    private var keyboards: Set<String> = emptySet()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    closeShade(false)
                    hideStrip()
                }
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> updateStrip()
                // home or recents pressed while the shade is open
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> closeShade(true)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Fonts.init(this)
        prefs = Prefs(this)
        wm = getSystemService(WindowManager::class.java) ?: return
        keyboards = try {
            getSystemService(InputMethodManager::class.java)?.inputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
        } catch (t: Throwable) {
            emptySet()
        }
        val f = IntentFilter()
        f.addAction(Intent.ACTION_SCREEN_OFF)
        f.addAction(Intent.ACTION_SCREEN_ON)
        f.addAction(Intent.ACTION_USER_PRESENT)
        @Suppress("DEPRECATION")
        f.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, f)
        listenForWindows(false)
        updateStrip()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        try {
            unregisterReceiver(receiver)
        } catch (t: Throwable) {
            // never registered
        }
        closeShade(false)
        hideStrip()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // an open shade finishes as it is; the next swipe builds a window with the new sizes
        cachedWindow = null
        // rotation changes the width of the catch zone
        if (stripShown) {
            hideStrip()
            updateStrip()
        }
    }

    /** Asks for window events only while the shade is open: the rest of the time, zero traffic. */
    private fun listenForWindows(on: Boolean) {
        try {
            val info = serviceInfo ?: return
            info.eventTypes = if (on) AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED else 0
            serviceInfo = info
        } catch (t: Throwable) {
            // keep the declared configuration
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (shadeWindow == null || SystemClock.uptimeMillis() - openedAt < 700L) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString().orEmpty()
        // our own windows and the keyboard hiding are not a reason to close; the home screen is
        if (pkg == packageName && cls != HomeActivity::class.java.name) return
        if (pkg == "com.android.systemui" || pkg in keyboards) return
        // another app or the home screen came to the front: the shade steps aside
        closeShade(true)
    }

    // ------------------------------------------------------------ the catch strip

    private fun locked(): Boolean = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    fun updateStrip() {
        if (!::wm.isInitialized || locked()) return
        val v = strip ?: makeStrip().also { strip = it }
        val lp = stripParams()
        try {
            if (stripShown) wm.updateViewLayout(v, lp) else wm.addView(v, lp)
            stripShown = true
        } catch (t: Throwable) {
            stripShown = false
        }
    }

    private fun hideStrip() {
        val v = strip ?: return
        if (!stripShown) return
        stripShown = false
        try {
            wm.removeView(v)
        } catch (t: Throwable) {
            // already gone
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeStrip(): View {
        val v = View(this)
        v.contentDescription = "Шторка WIRED"
        var sx = 0f
        var sy = 0f
        var fired = false
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = ev.rawX
                    sy = ev.rawY
                    fired = false
                }
                MotionEvent.ACTION_MOVE -> if (!fired) {
                    val dy = ev.rawY - sy
                    if (dy > dp(20f) && dy > abs(ev.rawX - sx)) {
                        fired = true
                        openShade()
                    }
                }
            }
            true
        }
        return v
    }

    private fun screenWidth(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.width() else resources.displayMetrics.widthPixels

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun systemDimen(name: String, fallbackDp: Int): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        val v = if (id != 0) resources.getDimensionPixelSize(id) else 0
        return if (v > 0) v else dpi(fallbackDp)
    }

    private fun stripParams(): WindowManager.LayoutParams {
        val zone = prefs.topZone
        val w = if (zone == Prefs.ZONE_FULL) WindowManager.LayoutParams.MATCH_PARENT else screenWidth() / 2
        val lp = WindowManager.LayoutParams(
            w,
            systemDimen("status_bar_height", 28),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or if (zone == Prefs.ZONE_RIGHT) Gravity.END else Gravity.START
        overStatusBar(lp)
        lp.title = "WIRED shade catcher"
        return lp
    }

    /** Lets a window sit over the status bar and the camera cutout instead of below them. */
    private fun overStatusBar(lp: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= 30) {
            lp.fitInsetsTypes = 0
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    // ------------------------------------------------------------ the shade window

    private fun openShade() {
        if (locked() || shadeWindow != null) return
        // the app's own theme, so switches, sliders and scroll edges look as they do in WIRED
        val win = cachedWindow ?: ShadeWindow(android.view.ContextThemeWrapper(this, R.style.Theme_Wired)).also { cachedWindow = it }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // focusable on purpose: the back gesture reaches this window and closes the shade
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        overStatusBar(lp)
        lp.title = "WIRED shade"
        try {
            wm.addView(win, lp)
        } catch (t: Throwable) {
            return
        }
        shadeWindow = win
        openedAt = SystemClock.uptimeMillis()
        listenForWindows(true)
        win.panel.applyStyle(prefs.accent)
        // removed again before this ran (screen off right after the swipe): don't open a hidden panel
        win.post { if (shadeWindow === win) win.panel.open() }
    }

    private fun closeShade(animated: Boolean) {
        val win = shadeWindow ?: return
        if (win.panel.isOpen) win.panel.close(animated) else removeShade(win)
    }

    private fun removeShade(win: ShadeWindow) {
        if (shadeWindow === win) {
            shadeWindow = null
            listenForWindows(false)
        }
        try {
            wm.removeView(win)
        } catch (t: Throwable) {
            // already gone
        }
    }

    override fun systemShade() {
        closeShade(false)
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
    }

    override fun closeShade() = closeShade(true)

    // an app or a settings panel opens from the shade: get out of its way at once
    override fun beforeLaunch() = closeShade(false)

    /** The overlay window: the shade itself, the swipe up that closes it, and the back gesture. */
    private inner class ShadeWindow(ctx: Context) : FrameLayout(ctx) {
        val panel = QuickPanelView(ctx, this@WiredShadeService)
        private var downOnList = false
        private var listAtBottom = true

        private val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val dy = e2.rawY - start.rawY
                val dx = e2.rawX - start.rawX
                if (dy < -dp(56f) && abs(dy) > abs(dx) * 1.3f && abs(velocityY) > 300f && (!downOnList || listAtBottom)) {
                    closeShade(true)
                    return true
                }
                return false
            }
        })

        init {
            panel.onClosed = { removeShade(this) }
            addView(panel, LayoutParams(MATCH, MATCH))
            setOnApplyWindowInsetsListener { _, insets ->
                val b = systemInsets(insets)
                val top = if (b.top > 0) b.top else systemDimen("status_bar_height", 28)
                val bottom = if (b.bottom > 0) b.bottom else systemDimen("navigation_bar_height", 16)
                panel.setInsets(top, bottom, b.left, b.right)
                insets
            }
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                downOnList = panel.isTouchOnList(ev.rawX, ev.rawY)
                listAtBottom = panel.listAtBottom()
            }
            detector.onTouchEvent(ev)
            return super.dispatchTouchEvent(ev)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) closeShade(true)
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    companion object {
        @Volatile
        var instance: WiredShadeService? = null
            private set

        fun component(ctx: Context) = ComponentName(ctx, WiredShadeService::class.java)

        /** Whether the user switched the service on in Accessibility settings. */
        fun isEnabled(ctx: Context): Boolean = try {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val me = component(ctx)
            list.split(':').any { ComponentName.unflattenFromString(it) == me }
        } catch (t: Throwable) {
            instance != null
        }

        /** Opens Samsung's own shade through the official accessibility action, if the service runs. */
        fun showSystemShade(): Boolean = instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) == true

        /** Settings changed the catch zone. */
        fun refresh() {
            instance?.let {
                it.hideStrip()
                it.updateStrip()
            }
        }
    }
}
