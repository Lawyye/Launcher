package com.lawyye.wired

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.util.LruCache
import android.widget.ImageView
import java.text.Collator
import java.util.concurrent.Executors

class AppEntry(
    val label: String,
    val info: LauncherActivityInfo,
    val user: UserHandle,
    val isWork: Boolean,
    val category: Int,
) {
    val component: ComponentName = info.componentName
    val key: String = component.flattenToString() + if (isWork) "#w" else ""
    val packageName: String get() = component.packageName
    val section: String = sectionOf(label)
    val search: String = label.lowercase()
    val searchPkg: String = component.packageName.lowercase()

    companion object {
        const val CAT_ALL = 0
        const val CAT_SOCIAL = 1
        const val CAT_MEDIA = 2
        const val CAT_TOOLS = 3
        const val CAT_GAMES = 4
        const val CAT_SYSTEM = 5
        const val CAT_OTHER = 6

        val CATEGORY_TITLES = listOf("Все", "Соцсети", "Медиа", "Инструменты", "Игры", "Система")
        private val CATEGORY_META = listOf("", "соцсети", "медиа", "инструменты", "игры", "система", "другое")

        fun categoryMeta(c: Int): String = CATEGORY_META.getOrElse(c) { "" }

        fun sectionOf(label: String): String {
            val c = label.trim().firstOrNull() ?: return "#"
            return if (c.isLetter()) c.uppercaseChar().toString() else "#"
        }

        private val SOCIAL = listOf(
            "telegram", "whatsapp", "vkontakte", ".vk.", "instagram", "discord", "viber", "snapchat",
            "facebook", "twitter", "tiktok", "messenger", "thoughtcrime", "signal", "ok.android", "max.messenger",
        )
        private val MEDIA = listOf(
            "music", "spotify", "youtube", "video", "player", "camera", "gallery", "photo", "podcast",
            "radio", "netflix", "kinopoisk", "ivi", "okko", "wink", "soundcloud", "twitch", "yandex.music",
        )

        @Suppress("DEPRECATION")
        fun categoryOf(ai: ApplicationInfo): Int {
            val pkg = ai.packageName.lowercase()
            if (Build.VERSION.SDK_INT >= 31 && ai.category == ApplicationInfo.CATEGORY_ACCESSIBILITY) return CAT_SYSTEM
            when (ai.category) {
                ApplicationInfo.CATEGORY_GAME -> return CAT_GAMES
                ApplicationInfo.CATEGORY_SOCIAL -> return CAT_SOCIAL
                ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> return CAT_MEDIA
                ApplicationInfo.CATEGORY_PRODUCTIVITY, ApplicationInfo.CATEGORY_MAPS, ApplicationInfo.CATEGORY_NEWS -> return CAT_TOOLS
            }
            if ((ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0 || pkg.contains("game")) return CAT_GAMES
            if (SOCIAL.any { pkg.contains(it) }) return CAT_SOCIAL
            if (MEDIA.any { pkg.contains(it) }) return CAT_MEDIA
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return CAT_SYSTEM
            return CAT_OTHER
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

    var loaded = false
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
                    override fun onPackageRemoved(packageName: String?, user: UserHandle?) = changed(app, packageName)
                    override fun onPackageAdded(packageName: String?, user: UserHandle?) = changed(app, packageName)
                    override fun onPackageChanged(packageName: String?, user: UserHandle?) = changed(app, packageName)
                    override fun onPackagesAvailable(names: Array<out String>?, user: UserHandle?, replacing: Boolean) =
                        changed(app, null)

                    override fun onPackagesUnavailable(names: Array<out String>?, user: UserHandle?, replacing: Boolean) =
                        changed(app, null)
                }, main)
                callbackRegistered = true
            }
        }
        if (!loaded) reload(app)
    }

    private fun changed(ctx: Context, pkg: String?) {
        if (pkg != null) IconCache.invalidatePackage(pkg) else IconCache.clear()
        reload(ctx)
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
                    loaded = true
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
                val cat = try {
                    AppEntry.categoryOf(info.applicationInfo)
                } catch (t: Throwable) {
                    AppEntry.CAT_OTHER
                }
                out.add(AppEntry(label, info, user, user != me, cat))
            }
        }
        val collator = Collator.getInstance()
        collator.strength = Collator.PRIMARY
        out.sortWith { a, b -> collator.compare(a.label, b.label) }
        return out
    }
}

/**
 * Original app icons rendered once into small bitmaps and kept in memory, so scrolling never
 * decodes an icon twice. Loading happens on two background threads.
 */
object IconCache {

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)
    private val pending = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    private val cache: LruCache<String, Bitmap> = run {
        val budget = (Runtime.getRuntime().maxMemory() / 8).coerceIn(8L shl 20, 40L shl 20).toInt()
        object : LruCache<String, Bitmap>(budget) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }

    private fun cacheKey(app: AppEntry, px: Int) = app.key + "@" + px

    fun bind(iv: ImageView, app: AppEntry, px: Int) {
        val k = cacheKey(app, px)
        iv.tag = k
        val hit = cache.get(k)
        if (hit != null) {
            iv.setImageBitmap(hit)
            return
        }
        iv.setImageDrawable(null)
        load(iv.context, app, px) { bmp -> if (iv.tag == k && bmp != null) iv.setImageBitmap(bmp) }
    }

    fun load(ctx: Context, app: AppEntry, px: Int, cb: (Bitmap?) -> Unit) {
        val k = cacheKey(app, px)
        cache.get(k)?.let {
            cb(it)
            return
        }
        val list = pending[k]
        if (list != null) {
            list.add(cb)
            return
        }
        pending[k] = mutableListOf(cb)
        val appCtx = ctx.applicationContext
        pool.execute {
            val bmp = render(appCtx, app, px)
            main.post {
                if (bmp != null) cache.put(k, bmp)
                val cbs = pending.remove(k) ?: return@post
                for (c in cbs) c(bmp)
            }
        }
    }

    @Volatile
    private var warmGeneration = 0

    /**
     * Fills the cache in the background, in the order given (dock first, then the drawer).
     * Runs as one task on one thread, so icons a visible row asks for are never stuck behind it;
     * a newer call (apps changed again) stops the older one. Never warms more than fits the cache.
     */
    fun prewarm(ctx: Context, apps: List<AppEntry>, px: Int) {
        val gen = ++warmGeneration
        val perIcon = px.toLong() * px * 4
        val room = if (perIcon > 0) (cache.maxSize() * 0.8 / perIcon).toInt() else 0
        val todo = apps.asSequence().filter { cache.get(cacheKey(it, px)) == null }.take(room).toList()
        if (todo.isEmpty()) return
        val appCtx = ctx.applicationContext
        pool.execute {
            for (a in todo) {
                if (gen != warmGeneration) return@execute
                val k = cacheKey(a, px)
                if (cache.get(k) != null) continue
                val bmp = render(appCtx, a, px) ?: continue
                main.post { if (cache.get(k) == null) cache.put(k, bmp) }
            }
        }
    }

    /** The system is short of memory and the launcher is in the background: keep only half. */
    /** Bytes the icon cache holds now, for the crash log. */
    fun bytes(): Int = cache.size()

    fun trim() {
        warmGeneration++
        cache.trimToSize(cache.maxSize() / 2)
    }

    private fun render(ctx: Context, app: AppEntry, px: Int): Bitmap? = try {
        val d = app.info.getBadgedIcon(ctx.resources.displayMetrics.densityDpi)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, px, px)
        d.draw(c)
        bmp
    } catch (t: Throwable) {
        null
    }

    fun invalidatePackage(pkg: String) {
        for (k in cache.snapshot().keys) {
            if (k.startsWith("$pkg/")) cache.remove(k)
        }
    }

    fun clear() = cache.evictAll()
}
