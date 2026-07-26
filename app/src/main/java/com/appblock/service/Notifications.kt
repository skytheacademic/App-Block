package com.appblock.service

/**
 * Every notification id the app posts, declared together.
 *
 * They live in one object because they were previously scattered across the classes that used them,
 * and that collided: the signal-drift prompt was given id 2, which the unlock-window notification
 * already owned. Ids are per-package, so the watchdog's "withdraw the prompt when healthy" pass —
 * running every 15 minutes, and cancelling on nearly every pass — would have silently pulled down the
 * notification announcing that a two-hour wait had finished, inside a window only fifteen minutes
 * long. A cancel is as destructive as a post when the id is wrong.
 *
 * Add new ids here, and add them to [ALL] so [com.appblock.service.NotificationIdTest] keeps them
 * distinct.
 */
internal object Notifications {

    /** Blocking is dead or degraded — ongoing, withdrawn by [Watchdog.report]. */
    const val HEALTH = 1

    /** The durable-change window is open — the one that announces a finished wait. */
    const val UNLOCK_WINDOW = 2

    /** Instagram's reel ids may have drifted — dismissable, withdrawn when re-confirmed. */
    const val SIGNAL_DRIFT = 3

    val ALL = listOf(HEALTH, UNLOCK_WINDOW, SIGNAL_DRIFT)
}
