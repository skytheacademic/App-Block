package com.appblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The safety property Batch 4 rests on: an on-device app picker must not become a bypass.
 *
 * Adding an app has to be free (you're binding yourself harder) while removing one has to cost the
 * 2-hour APPS window — otherwise "add it, then take it straight back off" is a way to reach a state
 * the lock is supposed to guard. [DurableChangeGate] already had the right shape for this: it walks
 * the *union* of old and new target keys and reads an absent target as fully open, so open membership
 * classifies correctly without a special case.
 */
class UserTargetGateTest {

    private val reddit = Target.forPackage("com.reddit.frontpage")
    private val blocked = TargetSettings(enabled = true, weekdayMinutes = 30, weekendMinutes = 30, exceptionMaxMinutes = 60)

    private val base = DurableSettings(
        version = 1,
        targets = linkedMapOf(Target.TIKTOK to blocked),
        exceptionWindowMinutes = 60,
    )
    private val withReddit = base.copy(targets = base.targets + (reddit to blocked))

    @Test fun `adding an app tightens, so it saves without the window`() {
        assertEquals(ChangeDirection.TIGHTEN, DurableChangeGate.classify(base, withReddit))
        val result = DurableChangeGate.applyChange(base, withReddit, unlocked = false)
        assertTrue(result is ChangeResult.Applied)
    }

    @Test fun `removing an added app loosens, so it needs the window`() {
        assertEquals(ChangeDirection.LOOSEN, DurableChangeGate.classify(withReddit, base))
        val result = DurableChangeGate.applyChange(withReddit, base, unlocked = false)
        assertTrue(result is ChangeResult.Blocked)
    }

    @Test fun `add-then-remove is not a free round trip`() {
        // The whole reason to check: if the pair netted out as neutral, the picker would be a way to
        // drop a block for free. Each leg is classified against the state actually persisted.
        val added = DurableChangeGate.applyChange(base, withReddit, unlocked = false)
        assertTrue(added is ChangeResult.Applied)
        val persisted = (added as ChangeResult.Applied).settings

        val removed = DurableChangeGate.applyChange(persisted, base, unlocked = false)
        assertTrue("removing what was just added must still cost a window", removed is ChangeResult.Blocked)
    }

    @Test fun `turning an added app off is gated just like removing it`() {
        val off = withReddit.copy(targets = withReddit.targets + (reddit to blocked.copy(enabled = false)))
        assertEquals(ChangeDirection.LOOSEN, DurableChangeGate.classify(withReddit, off))
    }

    @Test fun `an added app resolves from its package only while it has a rule`() {
        val active = withReddit.toRules().map { it.target }.toSet()
        assertEquals(reddit, AppTargets.targetFor("com.reddit.frontpage", active))

        // Removed (or disabled, which drops it from toRules) ⇒ the package stops resolving.
        assertNull(AppTargets.targetFor("com.reddit.frontpage", base.toRules().map { it.target }.toSet()))
    }

    @Test fun `a curated package keeps its built-in target even if a user entry exists for it`() {
        // Otherwise adding TikTok from the picker would shadow the curated target and quietly drop its
        // special handling.
        val shadowed = base.copy(targets = base.targets + (Target.forPackage("com.zhiliaoapp.musically") to blocked))
        val active = shadowed.toRules().map { it.target }.toSet()
        assertEquals(Target.TIKTOK, AppTargets.targetFor("com.zhiliaoapp.musically", active))
    }

    @Test fun `user targets carry their package, built-ins do not`() {
        assertEquals("com.reddit.frontpage", reddit.userPackage)
        assertNull(Target.TIKTOK.userPackage)
        assertTrue(AppTargets.isEnforced(reddit))
    }

    @Test fun `package keys survive the settings-string delimiters`() {
        assertTrue(Target.isEncodableKey(reddit.key))
        assertTrue(!Target.isEncodableKey("a,b"))
        assertTrue(!Target.isEncodableKey("a|b"))
        assertTrue(!Target.isEncodableKey(""))
    }

    /**
     * Audit finding B-5: a blocked service ships more than one app, and installing the other client
     * was a ~40-second permanent escape. These can only be pre-blocked here — the picker lists
     * installed apps, so an app you haven't installed yet is unreachable from the phone.
     */
    @Test fun `lite clients count against the same target as the full app`() {
        val active = emptySet<Target>()   // built-in mappings must not depend on the user's rule list
        assertEquals(Target.TIKTOK, AppTargets.targetFor("com.zhiliaoapp.musically.go", active))
        assertEquals(Target.X, AppTargets.targetFor("com.twitter.android.lite", active))
    }

    /**
     * Instagram Lite is whole-app on purpose: InstagramSurface keys on com.instagram.android and on
     * ids read from that app's tree, so Lite has no surface detection and would otherwise be a free
     * reel firehose. Instagram proper must stay unmapped, or feed/DMs/stories stop being free.
     */
    @Test fun `instagram lite is capped wholesale while instagram proper carries only its hours`() {
        assertEquals(
            Target.INSTAGRAM_REELS_EXPLORE,
            AppTargets.targetFor("com.instagram.lite", emptySet()),
        )
        // Changed 2026-08-06, and the old assertion (`null`) was right for its time: nothing mapped
        // Instagram proper, because being in the package said nothing about the *budget*. It still
        // says nothing about the budget — that arrives from InstagramSurface — but it now says
        // everything about the *hours*, which are app-wide. So the package resolves, and it resolves
        // to the schedule-only target rather than to anything that could meter feed or DMs.
        assertEquals(
            Target.INSTAGRAM_APP,
            AppTargets.targetFor(InstagramSurface.PACKAGE, emptySet()),
        )
    }

    /** The guarantee the rename above is really protecting: the package can never buy a cap. */
    @Test fun `the instagram package resolves to a schedule-only rule, never to a budget`() {
        val rule = DefaultRules.ruleFor(Target.INSTAGRAM_APP)
        assertEquals(RuleMode.ScheduleOnly, rule?.mode)
        assertNotNull(rule?.schedule)
    }
}
