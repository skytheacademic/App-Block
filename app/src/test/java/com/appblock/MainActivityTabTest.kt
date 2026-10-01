package com.appblock

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblock.ui.AppTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The notifications' deep link: they open on Lock, and nothing else picks a tab by accident. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MainActivityTabTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun `the notification intent opens the Lock tab`() {
        assertEquals(AppTab.LOCK, MainActivity.tabFrom(MainActivity.lockTabIntent(app)))
    }

    /** A tap while the app is open must reuse that screen, not stack a second copy on it. */
    @Test fun `the notification intent reuses an open screen`() {
        val flags = MainActivity.lockTabIntent(app).flags
        assertTrue(flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test fun `a plain launch asks for no tab`() {
        assertNull(MainActivity.tabFrom(Intent(app, MainActivity::class.java)))
        assertNull(MainActivity.tabFrom(null))
    }

    @Test fun `an unknown tab name is ignored`() {
        val intent = Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_TAB, "SETTINGS")
        assertNull(MainActivity.tabFrom(intent))
    }
}
