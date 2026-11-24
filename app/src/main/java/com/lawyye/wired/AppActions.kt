package com.lawyye.wired

import android.app.Activity
import android.app.ActivityOptions
import android.app.SearchManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View

object AppActions {

    fun launch(act: Activity, app: AppEntry, from: View?): Boolean {
        val la = act.getSystemService(LauncherApps::class.java) ?: return false
        return try {
            var bounds: Rect? = null
            var opts: Bundle? = null
            if (from != null && from.width > 0 && from.height > 0) {
                val loc = IntArray(2)
                from.getLocationOnScreen(loc)
                bounds = Rect(loc[0], loc[1], loc[0] + from.width, loc[1] + from.height)
                opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle()
            }
            la.startMainActivity(app.component, app.user, bounds, opts)
            Prefs(act).countLaunch(app.key)
            true
        } catch (e: Throwable) {
            act.toast("Не удалось открыть " + app.label)
            false
        }
    }

    fun info(act: Activity, app: AppEntry) {
        try {
            val la = act.getSystemService(LauncherApps::class.java)
            if (la != null) {
                la.startAppDetailsActivity(app.component, app.user, null, null)
                return
            }
        } catch (e: Throwable) {
            // fall through to the settings intent
        }
        safeStart(act, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.packageName)))
    }

    fun uninstall(act: Activity, app: AppEntry) {
        try {
            val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.packageName))
            i.putExtra(Intent.EXTRA_USER, app.user)
            act.startActivity(i)
        } catch (e: Throwable) {
            act.toast("Это приложение нельзя удалить")
        }
    }

    fun webSearch(act: Activity, query: String) {
        try {
            act.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query))
        } catch (e: Throwable) {
            safeStart(act, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
        }
    }

    fun mapsSearch(act: Activity, query: String) {
        safeStart(act, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))))
    }

    fun storeSearch(act: Activity, query: String) {
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(query))))
        } catch (e: Throwable) {
            safeStart(act, Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=" + Uri.encode(query))))
        }
    }

    /** Starts an activity from a screen or from the shade service (which needs a new task). */
    fun safeStart(ctx: Context, intent: Intent): Boolean {
        if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: Throwable) {
            ctx.toast("Нет приложения для этого действия")
            false
        }
    }
}

object HomeRole {
    const val REQ_ROLE = 4208

    @Suppress("DEPRECATION")
    fun isDefault(ctx: Context): Boolean {
        return try {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val r = ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
            r?.activityInfo?.packageName == ctx.packageName
        } catch (e: Throwable) {
            false
        }
    }

    @Suppress("DEPRECATION")
    fun request(act: Activity) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val rm = act.getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    act.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_ROLE)
                    return
                }
            } catch (e: Throwable) {
                // fall back to the system "default apps" screen
            }
        }
        try {
            act.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Throwable) {
            AppActions.safeStart(act, Intent(Settings.ACTION_SETTINGS))
        }
    }
}
