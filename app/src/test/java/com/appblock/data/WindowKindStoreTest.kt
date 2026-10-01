package com.appblock.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblock.engine.WindowKindMemo
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The memo's saved copy survives a new store instance, and a missing one loads as empty. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WindowKindStoreTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        app.getSharedPreferences("appblock_runtime", 0).edit().clear().commit()
    }

    @Test fun `nothing saved loads as empty`() {
        assertEquals(WindowKindMemo.Snapshot(emptySet(), emptySet()), WindowKindStore(app).load())
    }

    @Test fun `a saved snapshot round-trips through a fresh store`() {
        val saved = WindowKindMemo.Snapshot(
            application = setOf("com.sec.android.app.launcher", "com.instagram.android"),
            systemOnly = setOf("com.android.systemui"),
        )
        WindowKindStore(app).save(saved)
        assertEquals(saved, WindowKindStore(app).load())
    }

    @Test fun `a later save replaces the earlier one`() {
        val store = WindowKindStore(app)
        store.save(WindowKindMemo.Snapshot(emptySet(), setOf("com.sec.android.app.launcher")))
        store.save(WindowKindMemo.Snapshot(setOf("com.sec.android.app.launcher"), emptySet()))
        assertEquals(
            WindowKindMemo.Snapshot(setOf("com.sec.android.app.launcher"), emptySet()),
            WindowKindStore(app).load(),
        )
    }
}
