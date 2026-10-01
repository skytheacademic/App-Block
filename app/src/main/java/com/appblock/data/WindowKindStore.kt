package com.appblock.data

import android.content.Context
import com.appblock.engine.WindowKindMemo

/**
 * Keeps [WindowKindMemo] across restarts, so the volume panel is already known to be system chrome the
 * first time it is pressed over a block after a reboot or an update. See the memo's "Why it is saved".
 *
 * Runtime prefs, beside the canary's witnesses: none of this is policy, and losing it costs only the
 * cold-start flicker it exists to remove. A memo that has learned nothing is as safe as no memo at all,
 * so an unreadable or missing copy simply loads as empty.
 */
class WindowKindStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): WindowKindMemo.Snapshot = runCatching {
        WindowKindMemo.Snapshot(
            // Copied: getStringSet hands back the prefs' own instance, which must never be mutated.
            application = prefs.getStringSet(KEY_APPLICATION, null)?.toSet().orEmpty(),
            systemOnly = prefs.getStringSet(KEY_SYSTEM_ONLY, null)?.toSet().orEmpty(),
        )
    }.getOrDefault(EMPTY)

    fun save(snapshot: WindowKindMemo.Snapshot) {
        prefs.edit()
            .putStringSet(KEY_APPLICATION, snapshot.application)
            .putStringSet(KEY_SYSTEM_ONLY, snapshot.systemOnly)
            .apply()
    }

    private companion object {
        const val PREFS = "appblock_runtime"
        const val KEY_APPLICATION = "window_kinds_application"
        const val KEY_SYSTEM_ONLY = "window_kinds_system_only"
        val EMPTY = WindowKindMemo.Snapshot(emptySet(), emptySet())
    }
}
