package com.lawyye.wired

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Installs the crash log before any screen starts. */
class WiredApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // before the first icon is drawn, so a cold start shows the pack's icons straight away
        IconPacks.select(Prefs(this).iconPack)
    }
}

/**
 * A local crash log: WIRED has no internet permission, so nothing is sent anywhere. The last
 * crash is written to a private file; the system's own record of why the process ended
 * (freezes, native crashes, kills for memory) is read on Android 11+. Both are shown in
 * Settings → система → Журнал сбоев, with a button to copy the text.
 */
object CrashLog {
    private const val FILE = "crash.txt"
    private const val MAX_CHARS = 48_000

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(app, thread, error)
            } catch (t: Throwable) {
                // never let the logger hide the original crash
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(ctx: Context, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val text = header() + "поток: " + thread.name + "\n\n" + sw
        File(ctx.filesDir, FILE).writeText(text.take(MAX_CHARS))
    }

    private fun header(): String =
        "WIRED " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n" +
            stamp(System.currentTimeMillis()) + "\n" +
            "Android " + Build.VERSION.RELEASE + " · " + Build.MANUFACTURER + " " + Build.MODEL + "\n"

    private fun stamp(t: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))

    /** When the last crash happened, for the settings row; null when there is nothing to show. */
    fun lastCrashTime(ctx: Context): Long? {
        val f = File(ctx.filesDir, FILE)
        return if (f.exists()) f.lastModified() else null
    }

    /** Everything known: the last Java crash and the system's recent exit records. */
    fun report(ctx: Context): String {
        val sb = StringBuilder()
        val f = File(ctx.filesDir, FILE)
        if (f.exists()) {
            sb.append("== последний сбой ==\n").append(f.readText()).append("\n")
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val exits = exits(ctx)
            if (exits.isNotEmpty()) sb.append("== как завершался WIRED (по данным системы) ==\n").append(exits)
        }
        if (sb.isEmpty()) return ""
        return header() + "\n" + sb + "\n== сейчас ==\n" + now(ctx)
    }

    /** Memory and services at the moment the log is opened. */
    private fun now(ctx: Context): String {
        val rt = Runtime.getRuntime()
        val heap = (rt.totalMemory() - rt.freeMemory()) / MB
        val native = android.os.Debug.getNativeHeapAllocatedSize() / MB
        val pss = android.os.Debug.getPss() / 1024
        return "память WIRED: " + pss + " МБ всего (Java " + heap + ", native " + native +
            ", иконки " + IconCache.bytes() / MB + ")\n" +
            "шторка сверху: " + (if (WiredShadeService.isEnabled(ctx)) "включена" else "выключена") + "\n" +
            "доступ к уведомлениям: " + (if (NotifHub.hasAccess(ctx)) "есть" else "нет") + "\n" +
            "набор иконок: " + Prefs(ctx).iconPack.ifEmpty { "нет" } + "\n"
    }

    private const val MB = 1024 * 1024

    private fun importanceName(i: Int): String = when {
        i <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "на экране"
        i <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "заметен"
        i <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "служба"
        else -> "в фоне"
    }

    @android.annotation.TargetApi(30)
    private fun exits(ctx: Context): String {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return ""
        val list = try {
            am.getHistoricalProcessExitReasons(ctx.packageName, 0, 6)
        } catch (t: Throwable) {
            return ""
        }
        val sb = StringBuilder()
        for (e in list) {
            sb.append(stamp(e.timestamp)).append(" · ").append(reasonName(e.reason))
            // where WIRED stood when it died, and how big it was: tells whether its own size mattered
            sb.append(" · ").append(importanceName(e.importance))
            if (e.rss > 0) sb.append(" · ").append(e.rss / 1024).append(" МБ")
            e.description?.takeIf { it.isNotBlank() }?.let { sb.append(" · ").append(it) }
            sb.append("\n")
            // a freeze (ANR) comes with the main thread's stack: the first lines name the culprit
            if (e.reason == ApplicationExitInfo.REASON_ANR) {
                try {
                    e.traceInputStream?.bufferedReader()?.use { r ->
                        r.lineSequence().take(40).forEach { sb.append("    ").append(it).append("\n") }
                    }
                } catch (t: Throwable) {
                    // no trace kept
                }
            }
        }
        return sb.toString()
    }

    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "сбой (Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "сбой (native)"
        ApplicationExitInfo.REASON_ANR -> "завис (ANR)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "выгружен: мало памяти"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "выгружен: слишком много ресурсов"
        ApplicationExitInfo.REASON_SIGNALED -> "остановлен системой"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "остановлен вручную"
        ApplicationExitInfo.REASON_USER_STOPPED -> "принудительно остановлен"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "изменились разрешения"
        ApplicationExitInfo.REASON_EXIT_SELF -> "завершился сам"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "завершился вслед за другим процессом"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "не смог запуститься"
        ApplicationExitInfo.REASON_FREEZER -> "заморожен системой"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "изменилось состояние пакета"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "обновление WIRED"
        else -> "причина $r"
    }

    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
    }
}
