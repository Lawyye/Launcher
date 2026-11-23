package com.lawyye.wired

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import java.text.Collator
import java.util.concurrent.Executors

class AppEntry(
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val icon: Drawable?,
    val isWork: Boolean,
) {
    val key: String = component.flattenToString() + if (isWork) "#w" else ""
    val packageName: String get() = component.packageName
    val section: String = sectionOf(label)
    val search: String = (label + " " + component.packageName).lowercase()

    companion object {
        fun sectionOf(label: String): String {
            val c = label.trim().firstOrNull() ?: return "#"
            return if (c.isLetter()) c.uppercaseChar().toString() else "#"
        }
    }
}

/** Keeps the list of launchable apps (all profiles) and reloads it when packages change. */
object AppRepository {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = LinkedHashSet<() -> Unit>()
    private var callbackRegistered = false
    private var generation = 0

    @Volatile
    var apps: List<AppEntry> = emptyList()
        private set

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        if (!callbackRegistered) {
            val la = app.getSystemService(LauncherApps::class.java)
            if (la != null) {
                la.registerCallback(object : LauncherApps.Callback() {
                    override fun onPackageRemoved(packageName: String?, user: UserHandle?) = reload(app)
                    override fun onPackageAdded(packageName: String?, user: UserHandle?) = reload(app)
                    override fun onPackageChanged(packageName: String?, user: UserHandle?) = reload(app)
                    override fun onPackagesAvailable(
                        packageNames: Array<out String>?,
                        user: UserHandle?,
                        replacing: Boolean,
                    ) = reload(app)

                    override fun onPackagesUnavailable(
                        packageNames: Array<out String>?,
                        user: UserHandle?,
                        replacing: Boolean,
                    ) = reload(app)
                }, main)
                callbackRegistered = true
            }
        }
        reload(app)
    }

    fun reload(ctx: Context) {
        val app = ctx.applicationContext
        val gen = ++generation
        io.execute {
            val list = try {
                load(app)
            } catch (t: Throwable) {
                emptyList<AppEntry>()
            }
            main.post {
                if (gen == generation) {
                    apps = list
                    for (l in listeners.toList()) l()
                }
            }
        }
    }

    private fun load(ctx: Context): List<AppEntry> {
        val la = ctx.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val me = Process.myUserHandle()
        val profiles: List<UserHandle> = try {
            la.profiles
        } catch (t: Throwable) {
            listOf(me)
        }
        val density = ctx.resources.displayMetrics.densityDpi
        val out = ArrayList<AppEntry>()
        for (user in profiles) {
            val acts: List<LauncherActivityInfo> = try {
                la.getActivityList(null, user)
            } catch (t: Throwable) {
                emptyList<LauncherActivityInfo>()
            }
            for (info in acts) {
                val cn = info.componentName
                if (cn.packageName == ctx.packageName) continue
                val label = info.label?.toString()?.trim().orEmpty().ifEmpty { cn.packageName }
                val icon = try {
                    info.getBadgedIcon(density)
                } catch (t: Throwable) {
                    null
                }
                out.add(AppEntry(label, cn, user, icon, user != me))
            }
        }
        val collator = Collator.getInstance()
        collator.strength = Collator.PRIMARY
        out.sortWith(Comparator { a, b -> collator.compare(a.label, b.label) })
        return out
    }
}
