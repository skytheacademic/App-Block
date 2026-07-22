package com.appblock.security

import android.content.Context
import android.os.SystemClock
import com.appblock.BuildConfig
import com.appblock.engine.DurableUnlockManager
import com.appblock.engine.DurableUnlockState
import com.appblock.engine.EngineCodec
import com.appblock.engine.UnlockCategory
import com.appblock.service.AndroidClockIntegrity
import com.appblock.service.UnlockWindowWorker

/**
 * Persists the durable-unlock state in the lock prefs file. Reboots are handled by the boot count
 * baked into the state ([DurableUnlockManager]), so no separate tripwire is needed here.
 */
class DurableUnlockStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): DurableUnlockState = EngineCodec.decodeUnlock(prefs.getString(KEY, null))

    fun save(state: DurableUnlockState) {
        if (state is DurableUnlockState.Locked) {
            prefs.edit().remove(KEY).apply()
        } else {
            prefs.edit().putString(KEY, EngineCodec.encodeUnlock(state)).apply()
        }
    }

    private companion object {
        const val PREFS = "appblock_lock"
        const val KEY = "unlock_state"
    }
}

/**
 * Android orchestration of the delayed, single-use change window (CONSTRAINTS.md §6, 2026-07-22):
 * ticks the state off the monotonic clock + boot count, persists it, and schedules / cancels the
 * "window is open" notification. The wait is per-category (apps 2 h · websites 72 h, §2). The
 * throwaway `debugFast` build uses short durations so the whole flow can be verified on-device in
 * minutes instead of hours/days.
 */
class DurableUnlockController(private val context: Context) {

    private val store = DurableUnlockStore(context)
    private val integrity = AndroidClockIntegrity(context)

    private val windowMs = if (BuildConfig.FAST_CAPS) FAST_WINDOW_MS else DurableUnlockManager.DEFAULT_WINDOW_MS

    /** The current state, advanced past any elapsed deadline / reboot and persisted. */
    fun state(): DurableUnlockState {
        val current = store.load()
        val next = DurableUnlockManager.tick(current, SystemClock.elapsedRealtime(), integrity.bootCount())
        if (next != current) store.save(next)
        return next
    }

    /** Any window open, either category — see [DurableUnlockManager.isOpen] (stand-down concerns). */
    fun isOpen(): Boolean = state() is DurableUnlockState.Open

    /** Whether a change of [category] is authorized right now — the open window must match. */
    fun isOpenFor(category: UnlockCategory): Boolean =
        DurableUnlockManager.isOpenFor(state(), category)

    /** Start the per-category wait (call only after the stashed key verified) + schedule the notification. */
    fun request(category: UnlockCategory = UnlockCategory.APPS) {
        val waitMs = waitMsFor(category)
        val pending = DurableUnlockManager.request(
            SystemClock.elapsedRealtime(),
            integrity.bootCount(),
            category,
            waitMs,
            windowMs,
        )
        store.save(pending)
        UnlockWindowWorker.schedule(context, waitMs)
    }

    /** After one accepted change: relock and drop the pending notification. */
    fun consume() {
        store.save(DurableUnlockManager.consume())
        UnlockWindowWorker.cancel(context)
    }

    /** Cancel a wait/window without making a change (tightening direction — always allowed). */
    fun cancel() = consume()

    private fun waitMsFor(category: UnlockCategory): Long =
        if (BuildConfig.FAST_CAPS) {
            when (category) {
                UnlockCategory.APPS -> FAST_WAIT_MS
                UnlockCategory.WEBSITES -> FAST_WEBSITES_WAIT_MS
            }
        } else {
            category.defaultWaitMs
        }

    companion object {
        /** Fast-build durations so the flow is verifiable on-device without waiting hours. */
        const val FAST_WAIT_MS = 2L * 60L * 1000L           // apps: 2-minute wait
        const val FAST_WEBSITES_WAIT_MS = 4L * 60L * 1000L  // websites: 4 min — visibly longer, proves the category wiring
        const val FAST_WINDOW_MS = 60L * 1000L              // 1-minute window
    }
}
