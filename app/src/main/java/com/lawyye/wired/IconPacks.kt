package com.lawyye.wired

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.ConcurrentHashMap

/**
 * Icon packs from Google Play. Every pack ships the same index, made for ADW and Nova:
 * appfilter.xml maps an app's launcher activity to one of the pack's pictures. Apps the pack does
 * not cover keep their own icon. The index is read once, on an icon thread, when the first icon
 * is drawn after the pack was chosen.
 */
object IconPacks {

    class Pack(val pkg: String, val label: String)

    /** What icon packs answer to: ADW, Nova, a few older launchers, and Apex (a category). */
    private val ACTIONS = arrayOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.teslacoilsw.launcher.THEME",
        "com.fede.launcher.THEME_ICONPACK",
        "com.gau.go.launcherex.theme",
    )
    private const val APEX = "com.anddoes.launcher.THEME"

    /** The packs installed now, by name. */
    fun installed(ctx: Context): List<Pack> {
        val pm = ctx.packageManager
        val out = LinkedHashMap<String, Pack>()
        for (a in ACTIONS) collect(pm, Intent(a), out)
        collect(pm, Intent(Intent.ACTION_MAIN).addCategory(APEX), out)
        out.remove(ctx.packageName)
        return out.values.sortedBy { it.label.lowercase() }
    }

    private fun collect(pm: PackageManager, intent: Intent, out: MutableMap<String, Pack>) {
        val list: List<ResolveInfo> = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        } catch (t: Throwable) {
            emptyList()
        }
        for (ri in list) {
            val ai = ri.activityInfo ?: continue
            if (ai.packageName in out) continue
            val label = try {
                ai.applicationInfo.loadLabel(pm).toString()
            } catch (t: Throwable) {
                ai.packageName
            }
            out[ai.packageName] = Pack(ai.packageName, label)
        }
    }

    /** The name a pack is shown under, or null when it is no longer installed. */
    fun labelOf(ctx: Context, pkg: String): String? = try {
        val pm = ctx.packageManager
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    } catch (t: Throwable) {
        null
    }

    // ------------------------------------------------------------ the chosen pack

    /** The chosen pack's package, "" for the apps' own icons. Set on the main thread. */
    @Volatile
    private var wanted = ""

    private class Index(val pkg: String, val res: Resources?, val byComponent: Map<String, String>, val byPackage: Map<String, String>) {
        /** Resource ids by picture name: looking a name up is slow, each is looked up once. */
        val ids = ConcurrentHashMap<String, Int>()
    }

    /** Only one icon thread reads an index; the other waits for it instead of reading it twice. */
    private val lock = Any()

    @Volatile
    private var index: Index? = null

    /** Bumped by [reset]; an index read before a reset is used once but not kept. */
    @Volatile
    private var generation = 0

    /** Chooses the pack; true when that changes the icons, which must then be drawn again. */
    fun select(pkg: String): Boolean {
        if (pkg == wanted) return false
        wanted = pkg
        // the previous pack's index (it can be a few MB for a big pack) is not needed any more
        generation++
        index = null
        return true
    }

    fun isCurrent(pkg: String): Boolean = pkg.isNotEmpty() && pkg == wanted

    /** The pack was updated or removed: its index is read again the next time an icon is drawn. */
    fun reset() {
        // no lock: the main thread must never wait for an icon thread reading a big index
        generation++
        index = null
    }

    /** The pack's picture for [app], or null to draw the app's own icon. Runs on icon threads. */
    fun iconFor(ctx: Context, app: AppEntry, densityDpi: Int): Drawable? {
        val want = wanted
        if (want.isEmpty()) return null
        val idx = index?.takeIf { it.pkg == want } ?: synchronized(lock) {
            index?.takeIf { it.pkg == want } ?: run {
                val gen = generation
                read(ctx, want).also { if (gen == generation) index = it }
            }
        }
        val res = idx.res ?: return null
        val cn = app.component
        val name = idx.byComponent[cn.packageName + "/" + cn.className]
            // the app renamed its activity since the pack was made: the package still identifies it,
            // as long as the package has just this one icon in the drawer
            ?: idx.byPackage[cn.packageName]?.takeIf { AppRepository.apps.count { it.packageName == cn.packageName } <= 1 }
            ?: return null
        val id = idx.ids.getOrPut(name) {
            val d = res.getIdentifier(name, "drawable", idx.pkg)
            if (d != 0) d else res.getIdentifier(name, "mipmap", idx.pkg)
        }
        if (id == 0) return null
        return try {
            val d = res.getDrawableForDensity(id, densityDpi, null) ?: return null
            if (app.isWork) ctx.packageManager.getUserBadgedIcon(d, app.user) else d
        } catch (t: Throwable) {
            null
        }
    }

    private fun read(ctx: Context, pkg: String): Index {
        val res = try {
            ctx.packageManager.getResourcesForApplication(pkg)
        } catch (t: Throwable) {
            // uninstalled: the apps' own icons until another pack is chosen
            return Index(pkg, null, emptyMap(), emptyMap())
        }
        val byComponent = HashMap<String, String>()
        val byPackage = HashMap<String, String>()
        try {
            val id = res.getIdentifier("appfilter", "xml", pkg)
            if (id != 0) {
                val p = res.getXml(id)
                try {
                    parse(p, byComponent, byPackage)
                } finally {
                    p.close()
                }
            } else {
                res.assets.open("appfilter.xml").use { stream ->
                    val p = Xml.newPullParser()
                    p.setInput(stream, null)
                    parse(p, byComponent, byPackage)
                }
            }
        } catch (t: Throwable) {
            // a broken index: what was read before the error is kept
        }
        return Index(pkg, res, byComponent, byPackage)
    }

    private fun parse(p: XmlPullParser, byComponent: MutableMap<String, String>, byPackage: MutableMap<String, String>) {
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "item") {
                val comp = p.getAttributeValue(null, "component")
                val draw = p.getAttributeValue(null, "drawable")?.trim()?.substringAfterLast('/')
                if (comp != null && !draw.isNullOrEmpty()) {
                    val key = AppFilter.componentKey(comp)
                    if (key != null) {
                        byComponent.putIfAbsent(key, draw)
                        byPackage.putIfAbsent(key.substringBefore('/'), draw)
                    }
                }
            }
            ev = p.next()
        }
    }
}
