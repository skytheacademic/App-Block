package com.appblock.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.res.XmlResourceParser
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblock.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * Guards the accessibility service declaration, because getting it wrong fails *silently* — the
 * service still runs, still sees windows, and just never blocks.
 *
 * The one that bit us (Gate B, 2026-07-23): without `flagIncludeNotImportantViews` the framework
 * hands the service only views that are important for accessibility, and Instagram's reel signal
 * (`clips_viewer_view_pager`) is a bare non-clickable, non-focusable, unlabelled ViewPager — so it
 * was never in our tree and reels never blocked, while `uiautomator dump` (whose UiAutomation sets
 * that flag) showed the id fine. Every id in ig-dumps/MAPPING.md was read from such a dump, so the
 * live service must scan with the same flag or the whole mapping is unsound.
 *
 * The SDK pin matters here even though this test asserts on XML attributes and nothing SDK-specific:
 * with no `@Config`, Robolectric takes its SDK from `targetSdk`, so this was the one test in the suite
 * that would start downloading (and failing on) a new android-all jar the moment the toolchain moved.
 * Every other Robolectric test in the project already pins 34; this one was the exception.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AccessibilityConfigTest {

    private fun configAttrs(): XmlResourceParser {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val parser = app.resources.getXml(R.xml.accessibility_service_config)
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "accessibility-service") return parser
            event = parser.next()
        }
        throw AssertionError("no <accessibility-service> tag in accessibility_service_config.xml")
    }

    @Test
    fun scansTheSameTreeUiautomatorDumps() {
        val flags = configAttrs().getAttributeIntValue(ANDROID_NS, "accessibilityFlags", 0)
        assertTrue(
            "flagIncludeNotImportantViews missing: Instagram reel detection silently stops working",
            flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS != 0,
        )
    }

    @Test
    fun reportsResourceIds() {
        val flags = configAttrs().getAttributeIntValue(ANDROID_NS, "accessibilityFlags", 0)
        assertTrue(
            "flagReportViewIds missing: getViewIdResourceName() returns null, so no reel signal and " +
                "no browser url_bar is ever seen - every id-based rule blocks nothing",
            flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS != 0,
        )
    }

    @Test
    fun scansEveryVisibleWindow() {
        val flags = configAttrs().getAttributeIntValue(ANDROID_NS, "accessibilityFlags", 0)
        assertTrue(
            "flagRetrieveInteractiveWindows missing: split-screen panes stop being seen",
            flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0,
        )
    }

    @Test
    fun canReadWindowContent() {
        val parser = configAttrs()
        assertEquals(
            true,
            parser.getAttributeBooleanValue(ANDROID_NS, "canRetrieveWindowContent", false),
        )
    }

    /**
     * Third member of the "silently fatal declaration" family, and the only one whose failure is
     * triggered by someone else: Android 17's Advanced Protection Mode revokes accessibility from
     * every service not declaring itself a tool. Losing this line doesn't break a build or a test
     * run — it just means the next person to enable Advanced Protection turns the blocker off
     * permanently, with no key and no wait.
     */
    @Test
    fun declaresItselfAnAccessibilityTool() {
        val parser = configAttrs()
        assertTrue(
            "isAccessibilityTool missing: Android 17 Advanced Protection revokes the accessibility " +
                "permission from services that don't declare it, which is a one-toggle bypass",
            parser.getAttributeBooleanValue(ANDROID_NS, "isAccessibilityTool", false),
        )
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
