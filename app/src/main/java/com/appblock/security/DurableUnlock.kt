package com.appblock.security

import android.content.Context
import android.os.SystemClock
import com.appblock.BuildConfig
import com.appblock.engine.DurableUnlockManager
import com.appblock.engine.DurableUnlockState
import com.appblock.engine.EngineCodec
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
 * Android orchestration of the 2-hour, single-use change window (CONSTRAINTS.md §6, 2026-07-22): ticks
 * the state off the monotonic clock + boot count, persists it, and schedules / cancels the "window is
 * open" notification. The throwaway `debugFast` build uses short durations so the whole flow can be
 * verified on-device in minutes instead of hours.
 */
class DurableUnlockController(private val context: Context) {

    private val store = DurableUnlockStore(context)
    private val integrity = AndroidClockIntegrity(context)

    private val waitMs = if (BuildConfig.FAST_CAPS) FAST_WAIT_MS else DurableUnlockManager.DEFAULT_WAIT_MS
    private val windowMs = if (BuildConfig.FAST_CAPS) FAST_WINDOW_MS else DurableUnlockManager.DEFAULT_WINDOW_MS

    /** The current state, advanced past any elapsed deadline / reboot and persisted. */
    fun state(): DurableUnlockState {
        val current = store.load()
        val next = DurableUnlockManager.tick(current, SystemClock.elapsedRealtime(), integrity.bootCount())
        if (next != current) store.save(next)
        return next
    }

    fun isOpen(): Boolean = state() is DurableUnlockState.Open

    /** Start the 2-hour wait (call only after the stashed key verified) and schedule the notification. */
    fun request() {
        val pending = DurableUnlockManager.request(
            SystemClock.elapsedRealtime(),
            integrity.bootCount(),
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

    companion object {
        /** Fast-build durations so the flow is verifiable on-device without waiting hours. */
        const val FAST_WAIT_MS = 2L * 60L * 1000L      // 2-minute wait
        const val FAST_WINDOW_MS = 60L * 1000L         // 1-minute window
    }
}
