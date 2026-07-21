package com.appblock.data

import android.content.Context
import android.content.Intent

/**
 * Lists user-facing apps (those with a launcher icon). Uses the CATEGORY_LAUNCHER query so it
 * works without the QUERY_ALL_PACKAGES permission — the manifest <queries> block whitelists it.
 */
object InstalledAppsProvider {

    fun loadLaunchableApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        @Suppress("DEPRECATION")
        val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)

        return resolveInfos
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
