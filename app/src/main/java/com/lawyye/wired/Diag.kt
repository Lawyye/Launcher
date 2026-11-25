package com.lawyye.wired

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.ViewTreeObserver
import android.view.Window
import java.util.Locale

/**
 * Measurements taken on the phone itself, so claims about smoothness rest on numbers and not on
 * guesses. Settings → система → Диагностика.
 *
 * - Frames (only while "Замер плавности" is on): every frame the home window draws, sorted by
 *   what was on screen (home, drawer, shade). For each: the refresh rate the system actually ran
 *   (from each frame's deadline: 8.3 ms = 120 Hz, 16.7 ms = 60 Hz), the share of frames that took
 *   longer than their deadline, and the worst frame.
 * - Starts (always, it costs one timestamp): cold start of the process to the first frame, and
 *   the return home from an app to the first frame.
 * - The home-gesture contract: whether One UI tells WIRED where to animate a closing app
 *   (GestureNavContract). Only if it does could WIRED draw that animation itself.
 */
object Diag {
    const val HOME = 0
    const val DRAWER = 1
    const val SHADE = 2
    private val NAMES = arrayOf("главный экран", "меню приложений", "шторка")

    private class Bucket {
        var frames = 0L
        var late = 0L
        var deadlineSum = 0L
        var worstNs = 0L

        fun clear() {
            frames = 0L
            late = 0L
            deadlineSum = 0L
            worstNs = 0L
        }
    }

    private val buckets = Array(3) { Bucket() }

    /** What is on screen now; written on the main thread every frame, read by the metrics thread. */
    @Volatile
    var scene = HOME

    private var thread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private var sceneTracker: ViewTreeObserver.OnPreDrawListener? = null

    // starts
    private var processStarted = false
    var coldStartMs = -1L
        private set
    private val returns = ArrayDeque<Long>()
    private var restartAt = 0L

    // the home gesture
    var homeIntents = 0
        private set
    var contractIntents = 0
        private set

    fun isOn(ctx: Context): Boolean = ctx.getSharedPreferences("wired", Context.MODE_PRIVATE).getBoolean("diag_frames", false)

    fun setOn(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("wired", Context.MODE_PRIVATE).edit().putBoolean("diag_frames", on).apply()
    }

    // ------------------------------------------------------------ frames

    /** Starts counting frames of [act]'s window; [current] tells what is on screen. */
    fun attach(act: Activity, current: () -> Int) {
        if (listener != null || !isOn(act)) return
        val t = HandlerThread("wired-frames", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        thread = t
        @Suppress("DEPRECATION")
        val fallbackNs = (1_000_000_000.0 / (act.windowManager.defaultDisplay?.refreshRate ?: 60f)).toLong()
        val l = Window.OnFrameMetricsAvailableListener { _, m, _ -> record(m, fallbackNs) }
        listener = l
        act.window.addOnFrameMetricsAvailableListener(l, Handler(t.looper))
        val tracker = ViewTreeObserver.OnPreDrawListener {
            scene = current()
            true
        }
        sceneTracker = tracker
        act.window.decorView.viewTreeObserver.addOnPreDrawListener(tracker)
    }

    fun detach(act: Activity) {
        listener?.let {
            try {
                act.window.removeOnFrameMetricsAvailableListener(it)
            } catch (t: Throwable) {
                // never attached to this window
            }
        }
        sceneTracker?.let { act.window.decorView.viewTreeObserver.removeOnPreDrawListener(it) }
        listener = null
        sceneTracker = null
        thread?.quitSafely()
        thread = null
    }

    private fun record(m: FrameMetrics, fallbackNs: Long) {
        if (m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return
        val total = m.getMetric(FrameMetrics.TOTAL_DURATION)
        val deadline = if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.DEADLINE) else fallbackNs
        if (total <= 0L || deadline <= 0L) return
        val b = buckets[scene.coerceIn(0, 2)]
        synchronized(this) {
            b.frames++
            b.deadlineSum += deadline
            if (total > deadline) b.late++
            if (total > b.worstNs) b.worstNs = total
        }
    }

    // ------------------------------------------------------------ starts

    /** Called from the home screen's onCreate: the first creation in a process is a cold start. */
    fun onHomeCreated(act: Activity) {
        if (processStarted) return
        processStarted = true
        val started = Process.getStartUptimeMillis()
        firstFrame(act) { coldStartMs = it - started }
    }

    fun onHomeRestart() {
        restartAt = SystemClock.uptimeMillis()
    }

    fun onHomeStart(act: Activity) {
        val at = restartAt
        if (at == 0L) return
        restartAt = 0L
        firstFrame(act) {
            synchronized(this) {
                returns.addLast(it - at)
                while (returns.size > 20) returns.removeFirst()
            }
        }
    }

    private fun firstFrame(act: Activity, done: (Long) -> Unit) {
        val vto = act.window.decorView.viewTreeObserver
        vto.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                act.window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                done(SystemClock.uptimeMillis())
                return true
            }
        })
    }

    /** Every intent that brings the home screen back: does it carry the gesture contract? */
    fun onHomeIntent(extras: android.os.Bundle?) {
        homeIntents++
        if (extras?.containsKey("gesture_nav_contract_v1") == true) contractIntents++
    }

    // ------------------------------------------------------------ report

    fun clear() = synchronized(this) {
        buckets.forEach { it.clear() }
        returns.clear()
        homeIntents = 0
        contractIntents = 0
    }

    fun report(ctx: Context): String = synchronized(this) {
        val sb = StringBuilder()
        sb.append("WIRED ").append(BuildConfig.VERSION_NAME).append(" · Android ").append(Build.VERSION.RELEASE)
            .append(" · ").append(Build.MODEL).append("\n\n")

        sb.append("== плавность ==\n")
        if (!isOn(ctx)) {
            sb.append("замер выключен\n")
        } else if (buckets.all { it.frames == 0L }) {
            sb.append("кадров пока нет: открой меню, полистай, открой шторку\n")
        }
        for (i in buckets.indices) {
            val b = buckets[i]
            if (b.frames == 0L) continue
            val hz = 1_000_000_000.0 * b.frames / b.deadlineSum
            sb.append(NAMES[i]).append(": ").append(b.frames).append(" кадров · ")
                .append(String.format(Locale.US, "%.0f Гц", hz)).append(" · дольше бюджета ")
                .append(String.format(Locale.US, "%.1f%%", 100.0 * b.late / b.frames)).append(" · худший ")
                .append(String.format(Locale.US, "%.1f мс", b.worstNs / 1e6)).append("\n")
        }

        sb.append("\n== запуск ==\n")
        sb.append("холодный запуск: ").append(if (coldStartMs >= 0) "$coldStartMs мс" else "не было с момента запуска процесса").append("\n")
        if (returns.isNotEmpty()) {
            val sorted = returns.sorted()
            sb.append("возврат домой (до первого кадра): медиана ").append(sorted[sorted.size / 2])
                .append(" мс, худший ").append(sorted.last()).append(" мс, замеров ").append(sorted.size).append("\n")
        } else {
            sb.append("возврат домой: открой и закрой любое приложение\n")
        }

        sb.append("\n== жест «домой» ==\n")
        sb.append("возвратов на главный: ").append(homeIntents).append(", с GestureNavContract: ").append(contractIntents).append("\n")
        if (homeIntents > 0) {
            sb.append(
                if (contractIntents > 0) {
                    "One UI присылает контракт: анимацию закрытия приложения в иконку сделать можно\n"
                } else {
                    "One UI контракт не присылает: анимацию закрытия рисует сама система\n"
                },
            )
        }
        sb.toString()
    }
}
