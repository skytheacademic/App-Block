package com.appblock.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Notification ids are per-package, so two features sharing one silently fight over a single slot —
 * and the loser isn't only overwritten, it's *cancelled*, since the withdraw paths cancel by id too.
 *
 * That is exactly what happened: the signal-drift prompt was given id 2, which the unlock window
 * already owned, and the watchdog's healthy-pass withdraw would have pulled down the "your change
 * window is open" notification every 15 minutes — the notification announcing that a two-hour wait
 * had finished, inside a window fifteen minutes long.
 */
class NotificationIdTest {

    @Test fun `every notification id is distinct`() {
        assertEquals(
            "two features share a notification id — the withdraw path of one will cancel the other",
            Notifications.ALL.size,
            Notifications.ALL.toSet().size,
        )
    }
}
