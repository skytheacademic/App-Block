package com.appblock.data

import android.content.Context

/**
 * The list of package names the user has chosen to block, persisted in SharedPreferences.
 *
 * MVP storage: a plain string-set. The accessibility service reads this on every foreground
 * change, and the config screen writes to it. Later (friction layer) this grows into a real
 * schema — schedules, cooldown state, per-app budgets — most likely on DataStore or Room.
 */
class BlockedAppsRepository(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getBlockedPackages(): Set<String> =
        // Copy the returned set — the instance from getStringSet must not be mutated.
        prefs.getStringSet(KEY_BLOCKED, emptySet())?.toSet() ?: emptySet()

    fun isBlocked(packageName: String): Boolean =
        prefs.getStringSet(KEY_BLOCKED, emptySet())?.contains(packageName) == true

    fun setBlocked(packageName: String, blocked: Boolean) {
        val updated = getBlockedPackages().toMutableSet()
        if (blocked) updated.add(packageName) else updated.remove(packageName)
        prefs.edit().putStringSet(KEY_BLOCKED, updated).apply()
    }

    companion object {
        private const val PREFS = "appblock_prefs"
        private const val KEY_BLOCKED = "blocked_packages"
    }
}
