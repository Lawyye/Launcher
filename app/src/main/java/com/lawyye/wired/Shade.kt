package com.lawyye.wired

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow

/** Reads the phone's notifications for the WIRED shade (only after the user grants access). */
class WiredNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        NotifHub.refresh()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        NotifHub.refresh()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = NotifHub.refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = NotifHub.refresh()

    companion object {
        @Volatile
        var instance: WiredNotificationListener? = null
    }
}

class NotifItem(
    val key: String,
    val app: String,
    val title: String,
    val text: String,
    val time: Long,
    val intent: PendingIntent?,
    val clearable: Boolean,
    val autoCancel: Boolean,
    val icon: Drawable?,
)

object NotifHub {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    private val labels = HashMap<String, String>()

    var items: List<NotifItem> = emptyList()
        private set

    fun addListener(l: () -> Unit) = listeners.add(l)

    fun removeListener(l: () -> Unit) = listeners.remove(l)

    fun refresh() {
        val s = WiredNotificationListener.instance
        val list = if (s == null) emptyList() else build(s)
        main.post {
            items = list
            for (l in listeners.toList()) l()
        }
    }

    private fun build(s: NotificationListenerService): List<NotifItem> {
        val arr = try {
            s.activeNotifications ?: return emptyList()
        } catch (t: Throwable) {
            return emptyList()
        }
        return arr.filter { (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
            .sortedByDescending { it.postTime }
            .mapNotNull { sbn ->
                val n = sbn.notification
                val ex = n.extras
                val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = (ex?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
                if (title.isBlank() && text.isBlank()) return@mapNotNull null
                val icon = try {
                    n.smallIcon?.loadDrawable(s)
                } catch (t: Throwable) {
                    null
                }
                NotifItem(
                    sbn.key, label(s, sbn.packageName), title, text, sbn.postTime, n.contentIntent, sbn.isClearable,
                    (n.flags and Notification.FLAG_AUTO_CANCEL) != 0, icon,
                )
            }
            .take(40)
    }

    private fun label(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (t: Throwable) {
            pkg
        }
    }

    fun component(ctx: Context) = ComponentName(ctx, WiredNotificationListener::class.java)

    fun hasAccess(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 27) {
            ctx.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(component(ctx)) == true
        } else {
            Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(ctx.packageName + "/") == true
        }
    } catch (t: Throwable) {
        WiredNotificationListener.instance != null
    }

    fun openAccess(act: Activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(act).flattenToString())
                act.startActivity(i)
                return
            } catch (t: Throwable) {
                // fall back to the list of all listeners
            }
        }
        AppActions.safeStart(act, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun openAppInfo(act: Activity) =
        AppActions.safeStart(act, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + act.packageName)))

    fun open(act: Activity, item: NotifItem) {
        val pi = item.intent ?: return
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val o = ActivityOptions.makeBasic()
                o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(act, 0, null, null, null, null, o.toBundle())
            } else {
                pi.send()
            }
            if (item.autoCancel) dismiss(item.key)
        } catch (t: Throwable) {
            act.toast("Уведомление больше не открывается")
        }
    }

    fun dismiss(key: String) {
        try {
            WiredNotificationListener.instance?.cancelNotification(key)
        } catch (t: Throwable) {
            // the listener is gone
        }
    }

    fun clearAll() {
        try {
            WiredNotificationListener.instance?.cancelAllNotifications()
        } catch (t: Throwable) {
            // the listener is gone
        }
    }
}

/**
 * The WIRED shade: opened by swiping down on the home screen. Quick toggles, brightness and
 * media volume, the current player and the phone's notifications. The system shade is one tap
 * away (Samsung keeps its own shade inside other apps; no launcher can replace that one).
 */
@SuppressLint("ViewConstructor")
class QuickPanelView(private val act: Activity, private val host: Host) : FrameLayout(act) {

    interface Host {
        fun systemShade()
        fun closeShade()
    }

    private class Toggle(val icon: Int, val title: String, val tile: FrameLayout, val iconView: ImageView)

    var isOpen = false
        private set

    private var accent = Wired.BLUE
    private val glow = GradientDrawable()
    private val col = LinearLayout(act)
    private val time = act.label(44f, Wired.CLOCK, Fonts.clock)
    private val date = act.label(14f, Wired.TEXT_SOFT, Fonts.ui)
    private val toggles = ArrayList<Toggle>()
    private val toggleGrid = LinearLayout(act)
    private lateinit var brightness: SeekBar
    private lateinit var volume: SeekBar
    private val media = LinearLayout(act)
    private val mediaApp = act.meta("")
    private val mediaTitle = act.label(15f, Wired.CLOCK, Fonts.uiSemi)
    private val mediaArtist = act.label(13f, Wired.TEXT2, Fonts.ui)
    private val playButton: FrameLayout
    private val notifHead = act.meta("")
    private val clearButton = act.label(13f, Wired.ICON_SOFT, Fonts.uiSemi)
    private val listScroll = ScrollView(act)
    private val list = LinearLayout(act)
    private val access = LinearLayout(act)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())

    private val cameraManager = act.getSystemService(CameraManager::class.java)
    private var torchId: String? = null
    private var torchOn = false
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) {
                torchOn = enabled
                refreshToggles()
            }
        }
    }

    private var controller: MediaController? = null
    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = bindMedia()
        override fun onPlaybackStateChanged(state: PlaybackState?) = bindMedia()
        override fun onSessionDestroyed() = findController()
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { findController() }
    private val notifListener: () -> Unit = { bindNotifications() }

    init {
        visibility = GONE
        isClickable = true
        glow.gradientType = GradientDrawable.RADIAL_GRADIENT
        glow.setGradientCenter(0.5f, 0f)
        background = LayerDrawable(arrayOf(ColorDrawable(0xFA050913.toInt()), glow))
        col.orientation = LinearLayout.VERTICAL
        addView(col, LayoutParams(MATCH, MATCH))

        // header: time, date, system settings, system shade
        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val tcol = LinearLayout(act)
        tcol.orientation = LinearLayout.VERTICAL
        time.letterSpacing = -0.04f
        tcol.addView(time)
        tcol.addView(date)
        head.addView(tcol, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(act.iconButton(R.drawable.ic_gear, "Системные настройки") {
            AppActions.safeStart(act, Intent(Settings.ACTION_SETTINGS))
        })
        val sys = act.iconButton(R.drawable.ic_arrow_down, "Системная шторка") { host.systemShade() }
        (sys.layoutParams as LinearLayout.LayoutParams).leftMargin = act.dpi(8)
        head.addView(sys)
        col.addView(head)

        // toggles
        toggleGrid.orientation = LinearLayout.VERTICAL
        val tglp = LinearLayout.LayoutParams(MATCH, WRAP)
        tglp.topMargin = act.dpi(18)
        col.addView(toggleGrid, tglp)
        buildToggles()

        // sliders
        val sliders = LinearLayout(act)
        sliders.orientation = LinearLayout.VERTICAL
        sliders.background = act.rounded(Wired.SURFACE, 20f, 0x1A82B9FF)
        sliders.setPadding(act.dpi(14), act.dpi(8), act.dpi(10), act.dpi(8))
        brightness = act.wiredSlider(1000, 500, accent) { p -> setBrightness(p) }
        volume = act.wiredSlider(15, 7, accent) { p -> setVolume(p) }
        sliders.addView(sliderRow(R.drawable.ic_sun, "Яркость", brightness))
        sliders.addView(sliderRow(R.drawable.ic_volume, "Громкость", volume))
        val slp = LinearLayout.LayoutParams(MATCH, WRAP)
        slp.topMargin = act.dpi(14)
        col.addView(sliders, slp)

        // media
        media.orientation = LinearLayout.VERTICAL
        media.background = act.rounded(Wired.SURFACE, 20f, 0x1A82B9FF)
        media.setPadding(act.dpi(16), act.dpi(12), act.dpi(16), act.dpi(10))
        media.addView(mediaApp)
        mediaTitle.singleLine()
        val mtlp = LinearLayout.LayoutParams(MATCH, WRAP)
        mtlp.topMargin = act.dpi(6)
        media.addView(mediaTitle, mtlp)
        mediaArtist.singleLine()
        media.addView(mediaArtist)
        val controls = LinearLayout(act)
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER
        controls.addView(mediaButton(R.drawable.ic_prev, "Назад") { controller?.transportControls?.skipToPrevious() })
        playButton = mediaButton(R.drawable.ic_play, "Играть") { togglePlay() }
        controls.addView(playButton)
        controls.addView(mediaButton(R.drawable.ic_next, "Вперёд") { controller?.transportControls?.skipToNext() })
        val clp = LinearLayout.LayoutParams(MATCH, WRAP)
        clp.topMargin = act.dpi(6)
        media.addView(controls, clp)
        media.visibility = GONE
        val mlp = LinearLayout.LayoutParams(MATCH, WRAP)
        mlp.topMargin = act.dpi(14)
        col.addView(media, mlp)

        // notifications
        val nh = LinearLayout(act)
        nh.orientation = LinearLayout.HORIZONTAL
        nh.gravity = Gravity.CENTER_VERTICAL
        nh.addView(notifHead, LinearLayout.LayoutParams(0, WRAP, 1f))
        clearButton.text = "очистить"
        clearButton.setPadding(act.dpi(12), act.dpi(12), 0, act.dpi(12))
        clearButton.setOnClickListener { NotifHub.clearAll() }
        nh.addView(clearButton)
        val nhlp = LinearLayout.LayoutParams(MATCH, WRAP)
        nhlp.topMargin = act.dpi(14)
        col.addView(nh, nhlp)
        listScroll.isVerticalScrollBarEnabled = false
        listScroll.overScrollMode = OVER_SCROLL_NEVER
        list.orientation = LinearLayout.VERTICAL
        listScroll.addView(list)
        col.addView(listScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        buildAccessCard()
        col.addView(access, LinearLayout.LayoutParams(MATCH, WRAP))

        // close handle
        val handle = LinearLayout(act)
        handle.orientation = LinearLayout.VERTICAL
        handle.gravity = Gravity.CENTER_HORIZONTAL
        handle.setPadding(0, act.dpi(10), 0, act.dpi(4))
        val pill = View(act)
        pill.background = act.rounded(0x38D9E9FF, 2f)
        handle.addView(pill, LinearLayout.LayoutParams(act.dpi(36), act.dpi(4)))
        val ht = act.meta("swipe up · закрыть", 10f)
        ht.setPadding(0, act.dpi(6), 0, 0)
        handle.addView(ht)
        handle.isClickable = true
        handle.setOnClickListener { host.closeShade() }
        col.addView(handle, LinearLayout.LayoutParams(MATCH, WRAP))

        try {
            torchId = cameraManager?.cameraIdList?.firstOrNull { id ->
                val c = cameraManager.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (t: Throwable) {
            torchId = null
        }
    }

    fun applyStyle(accent: Int) {
        this.accent = accent
        glow.colors = intArrayOf(Wired.alpha(accent, 0x29), Wired.alpha(accent, 0))
        for (sb in listOf(brightness, volume)) {
            sb.progressTintList = android.content.res.ColorStateList.valueOf(accent)
            sb.thumbTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        refreshToggles()
    }

    fun setInsets(top: Int, bottom: Int, left: Int, right: Int) {
        col.setPadding(left + act.dpi(20), top + act.dpi(10), right + act.dpi(20), bottom + act.dpi(6))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        glow.gradientRadius = w * 0.85f
    }

    // ------------------------------------------------------------ open / close

    fun open() {
        if (isOpen) return
        isOpen = true
        visibility = VISIBLE
        refresh()
        alpha = 0f
        translationY = -height * 0.12f
        animate().alpha(1f).translationY(0f).setDuration(260L).setInterpolator(DecelerateInterpolator(2f)).start()
        startListening()
    }

    fun close(animated: Boolean) {
        if (!isOpen) return
        isOpen = false
        stopListening()
        if (animated) {
            animate().alpha(0f).translationY(-height * 0.08f).setDuration(200L).setInterpolator(DecelerateInterpolator())
                .withEndAction { visibility = GONE }.start()
        } else {
            animate().cancel()
            visibility = GONE
        }
    }

    /** Re-read every state (after returning from a system panel). */
    fun refresh() {
        updateTime()
        refreshToggles()
        readSliders()
        bindNotifications()
        findController()
    }

    fun updateTime() {
        val now = Date()
        time.text = timeFmt.format(now)
        date.text = dateFmt.format(now).lowercase()
    }

    private fun startListening() {
        NotifHub.addListener(notifListener)
        NotifHub.refresh()
        try {
            cameraManager?.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
        } catch (t: Throwable) {
            // no camera service
        }
        try {
            act.getSystemService(MediaSessionManager::class.java)
                ?.addOnActiveSessionsChangedListener(sessionsListener, NotifHub.component(act))
        } catch (t: Throwable) {
            // no notification access yet
        }
    }

    private fun stopListening() {
        NotifHub.removeListener(notifListener)
        try {
            cameraManager?.unregisterTorchCallback(torchCallback)
        } catch (t: Throwable) {
            // already gone
        }
        try {
            act.getSystemService(MediaSessionManager::class.java)?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (t: Throwable) {
            // already gone
        }
        controller?.unregisterCallback(mediaCallback)
        controller = null
    }

    fun isTouchOnList(rawX: Float, rawY: Float): Boolean {
        if (listScroll.visibility != VISIBLE) return false
        val loc = IntArray(2)
        listScroll.getLocationOnScreen(loc)
        return rawX >= loc[0] && rawX <= loc[0] + listScroll.width && rawY >= loc[1] && rawY <= loc[1] + listScroll.height
    }

    fun listAtBottom(): Boolean = !listScroll.canScrollVertically(1)

    // ------------------------------------------------------------ toggles

    private fun buildToggles() {
        val defs = listOf(
            R.drawable.ic_wifi to "Wi-Fi",
            R.drawable.ic_bluetooth to "Bluetooth",
            R.drawable.ic_data to "Интернет",
            R.drawable.ic_flashlight to "Фонарик",
            R.drawable.ic_moon to "Не беспокоить",
            R.drawable.ic_rotate to "Поворот",
            R.drawable.ic_map_pin to "Геолокация",
            R.drawable.ic_battery to "Экономия",
        )
        defs.chunked(4).forEachIndexed { r, rowDefs ->
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            for ((icon, title) in rowDefs) {
                val cell = LinearLayout(act)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER_HORIZONTAL
                cell.isClickable = true
                cell.isFocusable = true
                cell.contentDescription = title
                val tile = FrameLayout(act)
                val iv = ImageView(act)
                iv.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                tile.addView(iv, LayoutParams(act.dpi(22), act.dpi(22), Gravity.CENTER))
                cell.addView(tile, LinearLayout.LayoutParams(act.dpi(58), act.dpi(58)))
                val t = act.label(11f, Wired.TEXT_SOFT, Fonts.ui)
                t.text = title
                t.gravity = Gravity.CENTER
                t.maxLines = 2
                val tlp = LinearLayout.LayoutParams(WRAP, WRAP)
                tlp.topMargin = act.dpi(6)
                cell.addView(t, tlp)
                cell.pressScale()
                cell.setOnClickListener { onToggle(title) }
                cell.setOnLongClickListener {
                    openSettingsFor(title)
                    true
                }
                row.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
                toggles.add(Toggle(icon, title, tile, iv))
            }
            val rlp = LinearLayout.LayoutParams(MATCH, WRAP)
            if (r > 0) rlp.topMargin = act.dpi(12)
            toggleGrid.addView(row, rlp)
        }
    }

    private fun stateOf(title: String): Boolean? = try {
        when (title) {
            "Wi-Fi" -> act.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled
            "Bluetooth" -> Settings.Global.getInt(act.contentResolver, Settings.Global.BLUETOOTH_ON, 0) == 1
            "Фонарик" -> torchOn
            "Не беспокоить" -> act.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter.let {
                it != null && it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            }
            "Поворот" -> Settings.System.getInt(act.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
            "Геолокация" -> if (Build.VERSION.SDK_INT >= 28) {
                act.getSystemService(LocationManager::class.java)?.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                Settings.Secure.getInt(act.contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0
            }
            "Экономия" -> act.getSystemService(PowerManager::class.java)?.isPowerSaveMode
            else -> null
        }
    } catch (t: Throwable) {
        null
    }

    private fun refreshToggles() {
        for (t in toggles) {
            val on = stateOf(t.title) == true
            t.tile.background = if (on) {
                act.rounded(Wired.alpha(accent, 0x33), 20f, Wired.alpha(accent, 0x8C))
            } else {
                act.rounded(Wired.SURFACE2, 20f, Wired.LINE)
            }
            t.iconView.setImageDrawable(act.icon(t.icon, if (on) Wired.CLOCK else Wired.ICON_SOFT))
        }
    }

    private fun onToggle(title: String) {
        when (title) {
            "Wi-Fi" -> panel(Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS)
            "Bluetooth" -> AppActions.safeStart(act, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "Интернет" -> panel(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, Settings.ACTION_WIRELESS_SETTINGS)
            "Фонарик" -> {
                val id = torchId
                if (id == null) {
                    act.toast("Вспышка недоступна")
                } else {
                    try {
                        cameraManager?.setTorchMode(id, !torchOn)
                    } catch (t: Throwable) {
                        act.toast("Камера сейчас занята")
                    }
                }
            }
            "Не беспокоить" -> {
                val nm = act.getSystemService(NotificationManager::class.java)
                if (nm != null && nm.isNotificationPolicyAccessGranted) {
                    val on = stateOf(title) == true
                    nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    refreshToggles()
                } else {
                    act.toast("Разреши WIRED управлять режимом «Не беспокоить»")
                    AppActions.safeStart(act, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }
            }
            "Поворот" -> {
                if (Settings.System.canWrite(act)) {
                    val on = stateOf(title) == true
                    Settings.System.putInt(act.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 0 else 1)
                    refreshToggles()
                } else {
                    requestWriteSettings()
                }
            }
            "Геолокация" -> AppActions.safeStart(act, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            "Экономия" -> AppActions.safeStart(act, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        }
    }

    private fun openSettingsFor(title: String) {
        val action = when (title) {
            "Wi-Fi" -> Settings.ACTION_WIFI_SETTINGS
            "Bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "Интернет" -> Settings.ACTION_WIRELESS_SETTINGS
            "Не беспокоить" -> Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS
            "Поворот" -> Settings.ACTION_DISPLAY_SETTINGS
            "Геолокация" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "Экономия" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        AppActions.safeStart(act, Intent(action))
    }

    private fun panel(panelAction: String, fallback: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                act.startActivity(Intent(panelAction))
                return
            } catch (t: Throwable) {
                // no panel on this build
            }
        }
        AppActions.safeStart(act, Intent(fallback))
    }

    private fun requestWriteSettings() {
        act.toast("Разреши WIRED менять настройки — для яркости и поворота")
        AppActions.safeStart(act, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + act.packageName)))
    }

    // ------------------------------------------------------------ sliders

    private fun sliderRow(icon: Int, title: String, bar: SeekBar): View {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = act.dpi(48)
        val iv = act.iconView(icon, Wired.ICON_SOFT, 20)
        iv.contentDescription = title
        row.addView(iv)
        bar.contentDescription = title
        val lp = LinearLayout.LayoutParams(0, act.dpi(40), 1f)
        lp.leftMargin = act.dpi(8)
        row.addView(bar, lp)
        return row
    }

    private val maxBrightness: Int by lazy {
        val id = Resources.getSystem().getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
        val v = if (id != 0) Resources.getSystem().getInteger(id) else 255
        if (v in 1..10000) v else 255
    }

    private fun readSliders() {
        try {
            val b = Settings.System.getInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS, maxBrightness / 2)
            val f = (b.toFloat() / maxBrightness).coerceIn(0f, 1f).toDouble().pow(1 / 2.2)
            brightness.progress = (f * 1000).toInt()
        } catch (t: Throwable) {
            // unreadable
        }
        try {
            val am = act.getSystemService(AudioManager::class.java)
            if (am != null) {
                volume.max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                volume.progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
        } catch (t: Throwable) {
            // unreadable
        }
    }

    private var askedWrite = false

    private fun setBrightness(p: Int) {
        if (!Settings.System.canWrite(act)) {
            if (!askedWrite) {
                askedWrite = true
                requestWriteSettings()
            }
            return
        }
        try {
            val v = ((p / 1000.0).pow(2.2) * maxBrightness).toInt().coerceIn(1, maxBrightness)
            Settings.System.putInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
        } catch (t: Throwable) {
            // not allowed on this build
        }
    }

    private fun setVolume(p: Int) {
        try {
            act.getSystemService(AudioManager::class.java)?.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
        } catch (t: Throwable) {
            // blocked by do not disturb
        }
    }

    // ------------------------------------------------------------ media

    private fun mediaButton(icon: Int, desc: String, onClick: () -> Unit): FrameLayout {
        val f = FrameLayout(act)
        f.contentDescription = desc
        f.isClickable = true
        f.isFocusable = true
        f.background = act.ripple(null, 22f)
        val iv = ImageView(act)
        iv.setImageDrawable(act.icon(icon, Wired.TEXT))
        iv.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        f.addView(iv, LayoutParams(act.dpi(22), act.dpi(22), Gravity.CENTER))
        f.setOnClickListener { onClick() }
        f.layoutParams = LinearLayout.LayoutParams(act.dpi(64), act.dpi(44))
        return f
    }

    private fun findController() {
        controller?.unregisterCallback(mediaCallback)
        controller = try {
            act.getSystemService(MediaSessionManager::class.java)?.getActiveSessions(NotifHub.component(act))?.firstOrNull()
        } catch (t: Throwable) {
            null
        }
        controller?.registerCallback(mediaCallback, Handler(Looper.getMainLooper()))
        bindMedia()
    }

    private fun bindMedia() {
        val c = controller
        val md = c?.metadata
        if (c == null || md == null) {
            media.visibility = GONE
            return
        }
        media.visibility = VISIBLE
        mediaApp.text = try {
            val pm = act.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString().lowercase()
        } catch (t: Throwable) {
            c.packageName
        }
        mediaTitle.text = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "—"
        mediaArtist.text = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        (playButton.getChildAt(0) as ImageView).setImageDrawable(act.icon(if (playing) R.drawable.ic_pause else R.drawable.ic_play, Wired.CLOCK))
        playButton.background = act.ripple(act.rounded(Wired.alpha(accent, 0x29), 22f, Wired.alpha(accent, 0x66)), 22f, accent)
    }

    private fun togglePlay() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    // ------------------------------------------------------------ notifications

    private fun buildAccessCard() {
        access.orientation = LinearLayout.VERTICAL
        access.background = act.rounded(Wired.SURFACE, 20f, 0x1A82B9FF)
        access.setPadding(act.dpi(16), act.dpi(14), act.dpi(16), act.dpi(16))
        val t = act.label(15f, Wired.CLOCK, Fonts.uiSemi)
        t.text = "Уведомления здесь"
        access.addView(t)
        val m = act.label(13f, Wired.TEXT_SOFT, Fonts.ui)
        m.text = "Чтобы WIRED показывал уведомления и плеер, включи ему доступ к уведомлениям. " +
            "Если переключатель серый: Сведения о WIRED → ⋮ → «Разрешить ограниченные настройки»."
        m.setLineSpacing(0f, 1.35f)
        val mlp = LinearLayout.LayoutParams(MATCH, WRAP)
        mlp.topMargin = act.dpi(6)
        access.addView(m, mlp)
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(act.primaryButton("Дать доступ", accent) { NotifHub.openAccess(act) }, LinearLayout.LayoutParams(0, act.dpi(44), 1f))
        val info = act.ghostButton("Сведения") { NotifHub.openAppInfo(act) }
        val ilp = LinearLayout.LayoutParams(WRAP, act.dpi(44))
        ilp.leftMargin = act.dpi(10)
        row.addView(info, ilp)
        val rlp = LinearLayout.LayoutParams(MATCH, WRAP)
        rlp.topMargin = act.dpi(12)
        access.addView(row, rlp)
    }

    private fun bindNotifications() {
        val granted = NotifHub.hasAccess(act)
        access.visibility = if (granted) GONE else VISIBLE
        listScroll.visibility = if (granted) VISIBLE else GONE
        clearButton.visibility = if (granted && NotifHub.items.any { it.clearable }) VISIBLE else GONE
        val items = NotifHub.items
        notifHead.text = if (granted) "уведомления · " + items.size else "уведомления"
        list.removeAllViews()
        if (granted && items.isEmpty()) {
            val e = act.meta("тишина в сети")
            e.gravity = Gravity.CENTER
            e.setPadding(0, act.dpi(24), 0, act.dpi(24))
            list.addView(e, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        for (n in items) list.addView(notifRow(n))
    }

    private fun notifRow(n: NotifItem): View {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(act.dpi(12), act.dpi(12), act.dpi(4), act.dpi(12))
        row.background = act.ripple(act.rounded(Wired.SURFACE, 18f, 0x1482B9FF), 18f, accent)
        row.isClickable = true
        row.isFocusable = true
        val tile = FrameLayout(act)
        tile.background = act.rounded(Wired.alpha(accent, 0x1A), 11f)
        val iv = ImageView(act)
        n.icon?.mutate()?.let {
            it.setTint(accent)
            iv.setImageDrawable(it)
        }
        iv.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        tile.addView(iv, LayoutParams(act.dpi(18), act.dpi(18), Gravity.CENTER))
        row.addView(tile, LinearLayout.LayoutParams(act.dpi(34), act.dpi(34)))
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val meta = act.meta(n.app.lowercase() + " · " + DateUtils.getRelativeTimeSpanString(n.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().lowercase(), 10f)
        meta.singleLine()
        col.addView(meta)
        if (n.title.isNotBlank()) {
            val t = act.label(14f, Wired.CLOCK, Fonts.uiSemi)
            t.text = n.title
            t.singleLine()
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.topMargin = act.dpi(4)
            col.addView(t, lp)
        }
        if (n.text.isNotBlank()) {
            val t = act.label(13f, Wired.TEXT2, Fonts.ui)
            t.text = n.text
            t.maxLines = 2
            t.ellipsize = android.text.TextUtils.TruncateAt.END
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.topMargin = act.dpi(2)
            col.addView(t, lp)
        }
        val clp = LinearLayout.LayoutParams(0, WRAP, 1f)
        clp.leftMargin = act.dpi(12)
        row.addView(col, clp)
        if (n.clearable) {
            val x = FrameLayout(act)
            x.contentDescription = "Убрать"
            x.isClickable = true
            x.background = act.ripple(null, 16f)
            x.addView(act.iconView(R.drawable.ic_close, Wired.HINT, 16), LayoutParams(act.dpi(16), act.dpi(16), Gravity.CENTER))
            x.setOnClickListener { NotifHub.dismiss(n.key) }
            row.addView(x, LinearLayout.LayoutParams(act.dpi(40), act.dpi(40)))
        }
        row.setOnClickListener {
            NotifHub.open(act, n)
            host.closeShade()
        }
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.bottomMargin = act.dpi(8)
        row.layoutParams = lp
        return row
    }
}
