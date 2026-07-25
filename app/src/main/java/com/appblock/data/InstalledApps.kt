package com.appblock.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/** One pickable app: the package to block, and the name a human recognises it by. */
data class InstalledApp(val packageName: String, val label: String)

/**
 * The launchable apps on this phone, for the add-an-app picker (Batch 4).
 *
 * Lists apps with a launcher entry (~88 on the S25) rather than every installed package (~567) —
 * services, language packs and framework overlays aren't things you open and lose an hour to.
 *
 * Needs the `<queries>` MAIN/LAUNCHER block in the manifest. That's the targeted declaration; the
 * blunt `QUERY_ALL_PACKAGES` permission is deliberately *not* used — it's a sensitive permission and
 * this doesn't need it.
 */
object InstalledApps {

    fun launchable(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        // MATCH_ALL, not the default: the shell's `pm query-activities` uses MATCH_DEFAULT_ONLY and
        // undercounts badly — at Gate D it saw only Chrome while the app correctly saw Brave and
        // Samsung Internet too. Don't let that mistake back in here.
        val resolved: List<ResolveInfo> =
            runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_ALL) }.getOrDefault(emptyList())

        return resolved
            .asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != context.packageName }   // never offer to block ourselves
            .distinctBy { it.packageName }
            .map { InstalledApp(it.packageName, runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName)) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * The human-readable name for a package, or the package itself if it can't be resolved (uninstalled
     * since it was blocked, say). Never returns blank — a nameless row in the blocked list would be
     * un-actionable, and the user still has to be able to find and remove it.
     */
    fun labelFor(context: Context, packageName: String): String = runCatching {
        context.packageManager
            .getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0))
            .toString()
            .ifBlank { packageName }
    }.getOrDefault(packageName)
}
