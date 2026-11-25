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
        var severe = 0L
        var worstNs = 0L
        val worstParts = LongArray(PARTS.size)

        // the display's own refresh rate at each frame
        var hzSamples = 0L
        var hzHigh = 0L

        // the step between consecutive frames while something moves: 120 / 90 / 60 / 30 fps, stall
        val steps = LongArray(5)

        fun clear() {
            frames = 0L
            late = 0L
            severe = 0L
            worstNs = 0L
            worstParts.fill(0L)
            hzSamples = 0L
            hzHigh = 0L
            steps.fill(0L)
        }
    }

    /** What a frame's time went to; the worst frame of each screen is reported this way. */
    private val PARTS = arrayOf(
        FrameMetrics.UNKNOWN_DELAY_DURATION to "ожидание главного потока",
        FrameMetrics.INPUT_HANDLING_DURATION to "касания",
        FrameMetrics.ANIMATION_DURATION to "анимации",
        FrameMetrics.LAYOUT_MEASURE_DURATION to "раскладка",
        FrameMetrics.DRAW_DURATION to "запись отрисовки",
        FrameMetrics.SYNC_DURATION to "загрузка картинок в GPU",
        FrameMetrics.COMMAND_ISSUE_DURATION to "команды GPU",
        FrameMetrics.SWAP_BUFFERS_DURATION to "вывод кадра",
    )

    /** The display refresh rate, sampled on the main thread at every frame. */
    @Volatile
    private var displayHz = 60f
    private var lastVsync = 0L

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
            displayHz = if (Build.VERSION.SDK_INT >= 30) {
                act.display?.refreshRate ?: displayHz
            } else {
                @Suppress("DEPRECATION")
                act.windowManager.defaultDisplay?.refreshRate ?: displayHz
            }
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
        val vsync = m.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
        val b = buckets[scene.coerceIn(0, 2)]
        synchronized(this) {
            b.frames++
            if (total > deadline) b.late++
            if (total > deadline * 2) b.severe++
            if (total > b.worstNs) {
                b.worstNs = total
                for (i in PARTS.indices) b.worstParts[i] = m.getMetric(PARTS[i].first).coerceAtLeast(0L)
            }
            b.hzSamples++
            if (displayHz >= 100f) b.hzHigh++
            val step = vsync - lastVsync
            lastVsync = vsync
            // only steps between frames of one continuous motion; a pause longer than 250 ms is idle
            if (step in 1L..250_000_000L) {
                val ms = step / 1e6
                val k = when {
                    ms <= 9.5 -> 0
                    ms <= 12.5 -> 1
                    ms <= 17.5 -> 2
                    ms <= 40.0 -> 3
                    else -> 4
                }
                b.steps[k]++
            }
        }
    }

    // ------------------------------------------------------------ starts

    /** Called from the home screen's onCreate: the first creation in a process is a cold start. */
    fun onHomeCreated(act: Activity) {
        if (processStarted) return
        processStarted = true
        val started = Process.getStartUptimeMillis()
        val created = SystemClock.uptimeMillis()
        // the process may have been started earlier by the shade or notification service:
        // the wait before the home screen was asked for is not the launcher's start time
        processWaitMs = created - started
        firstFrame(act) {
            coldStartMs = it - started
            buildMs = it - created
        }
    }

    private var processWaitMs = -1L
    private var buildMs = -1L

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
        lastVsync = 0L
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
            fun pct(n: Long, of: Long) = if (of == 0L) "0%" else String.format(Locale.US, "%.0f%%", 100.0 * n / of)
            sb.append(NAMES[i]).append(": ").append(b.frames).append(" кадров\n")
            sb.append("  экран на 120 Гц: ").append(pct(b.hzHigh, b.hzSamples)).append(" кадров\n")
            val moving = b.steps.sum()
            if (moving > 0) {
                sb.append("  шаг между кадрами в движении: 120 fps ").append(pct(b.steps[0], moving))
                    .append(" · 90 ").append(pct(b.steps[1], moving))
                    .append(" · 60 ").append(pct(b.steps[2], moving))
                    .append(" · 30 ").append(pct(b.steps[3], moving))
                    .append(" · пропуски ").append(pct(b.steps[4], moving)).append("\n")
            }
            sb.append("  не успели к сроку: ").append(String.format(Locale.US, "%.1f%%", 100.0 * b.late / b.frames))
                .append(" · вдвое дольше срока: ").append(String.format(Locale.US, "%.1f%%", 100.0 * b.severe / b.frames)).append("\n")
            sb.append("  худший кадр ").append(String.format(Locale.US, "%.0f мс", b.worstNs / 1e6))
            val parts = PARTS.indices.filter { b.worstParts[it] >= 1_000_000L }.sortedByDescending { b.worstParts[it] }
            if (parts.isNotEmpty()) {
                sb.append(": ").append(
                    parts.joinToString(", ") { PARTS[it].second + " " + String.format(Locale.US, "%.0f", b.worstParts[it] / 1e6) },
                )
            }
            sb.append("\n")
        }

        sb.append("\n== запуск ==\n")
        if (coldStartMs >= 0) {
            sb.append("холодный запуск: ").append(coldStartMs).append(" мс (процесс до запроса экрана ")
                .append(processWaitMs).append(" мс, сборка экрана ").append(buildMs).append(" мс)\n")
        } else {
            sb.append("холодный запуск: не было с момента запуска процесса\n")
        }
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
