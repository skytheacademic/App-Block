package com.appblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.appblock.R
import com.appblock.engine.ChangeDirection
import com.appblock.engine.FieldChange
import com.appblock.engine.Target
import com.appblock.engine.UnlockCategory
import com.appblock.ui.theme.LampDimens
import com.appblock.ui.theme.LampType
import com.appblock.ui.theme.LocalLamp

/**
 * Lock answers "is it locked" and "is it actually protecting me" on one screen, because those are
 * the same question asked twice — a key that works and an Accessibility service that has been
 * switched off add up to no commitment at all.
 *
 * It is also the only commit point. The pinned save bar is gone; the diff lives here because this is
 * where the cost of a change is stated, and a change you have to come here to land is a change you
 * have read the price of.
 */

/** Which phase of the unlock cycle Lock is drawing. */
sealed interface LockState {
    /** No key configured yet — nothing is actually locked, and the screen says so first. */
    data object NoKey : LockState

    data object Locked : LockState

    /** The wait is running. [elapsedFraction] drives the progress track. */
    data class Pending(
        val category: UnlockCategory,
        val countdown: String,
        val elapsedFraction: Float,
        val startedAt: String,
        val opensAt: String,
    ) : LockState

    data class Open(val category: UnlockCategory, val countdown: String) : LockState
}

/** The receipt of a change that landed, shown until the user leaves the tab. */
data class LockReceipt(val title: String, val body: String)

/** One line of the protection list. A failing one becomes a card; a passing one stays a row. */
data class ProtectionItem(
    val title: String,
    val okLabel: String,
    val ok: Boolean,
    /** What is actually lost while this is failing — stated plainly, never as an alarm. */
    val consequence: String? = null,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

@Composable
fun LockScreen(
    state: LockState,
    receipt: LockReceipt?,
    changes: List<FieldChange>,
    direction: ChangeDirection,
    /** True when an *apps* window is open — the only thing that lets a loosening land. */
    appsWindowOpen: Boolean,
    /** How many separate loosenings the draft holds. A window covers exactly one. */
    looseningCount: Int,
    protection: List<ProtectionItem>,
    keyConfigured: Boolean,
    onCreateKey: () -> Unit,
    onStartWindow: () -> Unit,
    onCancelWindow: () -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
    onCopyRules: () -> Unit,
    labelFor: @Composable (Target) -> String,
    modifier: Modifier = Modifier,
) {
    val lamp = LocalLamp.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = stringResource(R.string.tab_lock),
            style = LampType.screenTitle,
            color = lamp.text,
            modifier = Modifier.padding(
                start = LampDimens.screenPadding,
                end = LampDimens.screenPadding,
                top = 16.dp,
            ),
        )

        // The receipt sits at the top rather than in a vanishing toast: "that was your one change"
        // is the most consequential sentence in the app, and it must still be readable a minute
        // later when you wonder whether the window is gone.
        if (receipt != null) {
            NoticeBanner(
                icon = phosphor(R.drawable.ic_ph_check_circle_fill),
                title = receipt.title,
                body = receipt.body,
                actionLabel = null,
                onAction = null,
                modifier = Modifier.padding(
                    start = LampDimens.screenPadding,
                    end = LampDimens.screenPadding,
                    top = 16.dp,
                ),
            )
        }

        when (state) {
            LockState.NoKey -> NoKeyBlock(onCreateKey)
            LockState.Locked -> RestingBlock(onStartWindow, keyConfigured)
            is LockState.Pending -> WaitingBlock(state, onCancelWindow)
            is LockState.Open -> OpenBlock(state, onCancelWindow)
        }

        if (changes.isNotEmpty()) {
            FadingRule(Modifier.padding(top = 22.dp))
            DiffSection(
                changes = changes,
                direction = direction,
                appsWindowOpen = appsWindowOpen,
                looseningCount = looseningCount,
                onDiscard = onDiscard,
                onSave = onSave,
                labelFor = labelFor,
            )
        }

        FadingRule(Modifier.padding(top = 22.dp))
        Column(
            Modifier.padding(
                start = LampDimens.screenPadding,
                end = LampDimens.screenPadding,
                top = 18.dp,
                bottom = 20.dp,
            ),
        ) {
            SectionLabel(stringResource(R.string.lock_protection), Modifier.padding(bottom = 10.dp))
            for (item in protection) {
                ProtectionRow(item)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhosphorIcon(phosphor(R.drawable.ic_ph_key), null, tint = lamp.neutral400, size = 17.dp)
                Spacer(Modifier.width(11.dp))
                Text(
                    text = if (keyConfigured) {
                        stringResource(R.string.lock_key_set)
                    } else {
                        stringResource(R.string.lock_key_missing)
                    },
                    style = LampType.rowTitle,
                    color = lamp.text,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.lock_key_hash_only),
                    style = LampType.metaSmall,
                    color = lamp.neutral600,
                )
            }
        }

        FadingRule()
        ExportSection(onCopy = onCopyRules)
    }
}

/**
 * Export the saved rules as text (C-7). Read-only by design — there is no matching import, and the
 * section says so, because "copy" without that sentence reads like a backup you could restore.
 *
 * It sits on Lock rather than on Apps or Sites because it covers *both*, and because this is the tab
 * about what the configuration costs and how it survives.
 */
@Composable
private fun ExportSection(onCopy: () -> Unit) {
    val lamp = LocalLamp.current
    Column(
        Modifier.padding(
            start = LampDimens.screenPadding,
            end = LampDimens.screenPadding,
            top = 18.dp,
            bottom = 24.dp,
        ),
    ) {
        SectionLabel(stringResource(R.string.lock_export), Modifier.padding(bottom = 10.dp))
        Text(
            text = stringResource(R.string.lock_export_body),
            style = LampType.metaSmall,
            color = lamp.neutral600,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        LampButton(
            text = stringResource(R.string.lock_export_copy),
            onClick = onCopy,
            style = LampButtonStyle.Secondary,
            minHeight = 40.dp,
        )
    }
}

@Composable
private fun NoKeyBlock(onCreateKey: () -> Unit) {
    val lamp = LocalLamp.current
    Column(
        Modifier.padding(
            start = LampDimens.screenPadding,
            end = LampDimens.screenPadding,
            top = 22.dp,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhosphorIcon(phosphor(R.drawable.ic_ph_lock_open), null, tint = lamp.accent, size = 22.dp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.lock_nokey_title), style = LampType.sectionTitle, color = lamp.text)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.lock_nokey_body),
                    style = LampType.body,
                    color = lamp.neutral500,
                )
            }
        }
        LampButton(
            text = stringResource(R.string.lock_create_key),
            onClick = onCreateKey,
            leadingIcon = phosphor(R.drawable.ic_ph_qr_code),
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            minHeight = 42.dp,
        )
    }
}

@Composable
private fun RestingBlock(onStartWindow: () -> Unit, keyConfigured: Boolean) {
    val lamp = LocalLamp.current
    Column {
        Row(
            modifier = Modifier.padding(
                start = LampDimens.screenPadding,
                end = LampDimens.screenPadding,
                top = 22.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhosphorIcon(phosphor(R.drawable.ic_ph_lock_simple_fill), null, tint = lamp.accent, size = 22.dp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.lock_locked), style = LampType.sectionTitle, color = lamp.text)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.lock_locked_sub),
                    style = LampType.body,
                    color = lamp.neutral500,
                )
            }
        }
        Column(
            Modifier.padding(
                start = LampDimens.screenPadding,
                end = LampDimens.screenPadding,
                top = 22.dp,
            ),
        ) {
            SectionLabel(stringResource(R.string.lock_costs), Modifier.padding(bottom = 9.dp))
            TableRule()
            CostRow(
                title = stringResource(R.string.lock_cost_apps),
                sub = stringResource(R.string.lock_cost_apps_sub),
                cost = stringResource(R.string.lock_cost_apps_value),
            )
            RowRule()
            CostRow(
                title = stringResource(R.string.lock_cost_sites),
                sub = stringResource(R.string.lock_cost_sites_sub),
                cost = stringResource(R.string.lock_cost_sites_value),
            )
            TableRule()
            LampButton(
                text = stringResource(R.string.lock_start_window),
                onClick = onStartWindow,
                enabled = keyConfigured,
                leadingIcon = phosphor(R.drawable.ic_ph_qr_code),
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                minHeight = 42.dp,
            )
        }
    }
}

@Composable
private fun CostRow(title: String, sub: String, cost: String) {
    val lamp = LocalLamp.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = LampType.rowTitle, color = lamp.text)
            Spacer(Modifier.height(2.dp))
            Text(sub, style = LampType.micro, color = lamp.neutral600)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = cost,
            style = LampType.numberSmall,
            color = lamp.neutral300,
        )
    }
}

@Composable
private fun WaitingBlock(state: LockState.Pending, onCancel: () -> Unit) {
    val lamp = LocalLamp.current
    Column(
        Modifier.padding(
            start = LampDimens.screenPadding,
            end = LampDimens.screenPadding,
            top = 24.dp,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhosphorIcon(phosphor(R.drawable.ic_ph_hourglass_medium), null, tint = lamp.accent, size = 17.dp)
            Spacer(Modifier.width(9.dp))
            Kicker(stringResource(R.string.lock_kicker_waiting, categoryLabel(state.category)))
        }
        Text(
            text = state.countdown,
            style = LampType.heroLarge,
            color = lamp.text,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = stringResource(R.string.lock_waiting_body),
            style = LampType.body,
            color = lamp.neutral400,
            modifier = Modifier.padding(top = 10.dp),
        )
        // A 3 dp track, not a Material progress bar: the wait is hours long, so this is a position
        // in a cycle rather than a task that is about to finish.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .height(3.dp)
                .background(lamp.accent900, RoundedCornerShape(2.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(state.elapsedFraction.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(lamp.accent500, RoundedCornerShape(2.dp)),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(
                text = stringResource(R.string.lock_started_at, state.startedAt),
                style = LampType.micro.copy(fontFeatureSettings = "tnum"),
                color = lamp.neutral600,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.lock_opens_at, state.opensAt),
                style = LampType.micro.copy(fontFeatureSettings = "tnum"),
                color = lamp.neutral600,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LampButton(
                text = stringResource(R.string.lock_cancel_wait),
                onClick = onCancel,
                style = LampButtonStyle.Secondary,
                minHeight = 40.dp,
            )
            Text(
                text = stringResource(R.string.lock_cancel_restarts),
                style = LampType.micro,
                color = lamp.neutral600,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun OpenBlock(state: LockState.Open, onCancel: () -> Unit) {
    val lamp = LocalLamp.current
    Column(
        Modifier.padding(
            start = LampDimens.screenPadding,
            end = LampDimens.screenPadding,
            top = 24.dp,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhosphorIcon(phosphor(R.drawable.ic_ph_lock_open_fill), null, tint = lamp.accent, size = 17.dp)
            Spacer(Modifier.width(9.dp))
            Kicker(stringResource(R.string.lock_kicker_open, categoryLabel(state.category)))
        }
        Text(
            text = state.countdown,
            style = LampType.heroLarge,
            color = lamp.text,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = stringResource(R.string.lock_open_body),
            style = LampType.body,
            color = lamp.neutral400,
            modifier = Modifier.padding(top = 10.dp),
        )
        LampButton(
            text = stringResource(R.string.lock_cancel_window),
            onClick = onCancel,
            style = LampButtonStyle.Secondary,
            modifier = Modifier.padding(top = 16.dp),
            minHeight = 40.dp,
        )
    }
}

@Composable
private fun categoryLabel(category: UnlockCategory): String = stringResource(
    if (category == UnlockCategory.WEBSITES) R.string.lock_category_websites else R.string.lock_category_apps,
)

/**
 * The diff that replaces the pinned save bar.
 *
 * Every pending change is listed, not only the loosening ones: the gate judges the whole settings
 * object at once, so one loosening anywhere gates everything — and a warning with nothing listed
 * under it (reported from the phone, 2026-07-25) leaves a blocked Save and nowhere to look. Showing
 * the full set means the culprit is always on screen, and the tightenings give it context.
 */
@Composable
private fun DiffSection(
    changes: List<FieldChange>,
    direction: ChangeDirection,
    appsWindowOpen: Boolean,
    looseningCount: Int,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
    labelFor: @Composable (Target) -> String,
) {
    val lamp = LocalLamp.current
    val loosens = direction == ChangeDirection.LOOSEN
    // One window buys one change (CONSTRAINTS §6). Enforced in the gate too, but stated here so the
    // rule is visible *before* Save rather than only as a rejection afterwards.
    val canSave = !loosens || (appsWindowOpen && looseningCount == 1)
    Column(
        Modifier.padding(
            start = LampDimens.screenPadding,
            end = LampDimens.screenPadding,
            top = 18.dp,
        ),
    ) {
        SectionLabel(stringResource(R.string.lock_unsaved), Modifier.padding(bottom = 10.dp))
        for (change in changes.take(MAX_LISTED_CHANGES)) {
            val who = change.target?.let { "${labelFor(it)} " }.orEmpty()
            val looser = change.direction == ChangeDirection.LOOSEN
            Text(
                text = buildString {
                    append(if (looser) "+ " else "− ")
                    append(who)
                    append(change.detail)
                    if (looser) append("  (loosens)")
                },
                style = LampType.mono,
                color = if (looser) lamp.accent300 else lamp.neutral500,
            )
        }
        if (changes.size > MAX_LISTED_CHANGES) {
            Text(
                text = stringResource(R.string.lock_more_changes, changes.size - MAX_LISTED_CHANGES),
                style = LampType.mono,
                color = lamp.neutral600,
            )
        }
        if (loosens && appsWindowOpen && looseningCount > 1) {
            // The window is open and still can't take this edit, which is the confusing case — so
            // name the count and say what to do about it, rather than leaving a greyed Save sitting
            // under an open window with no explanation.
            Text(
                text = stringResource(R.string.lock_loosening_count_hint, looseningCount),
                style = LampType.metaSmall,
                color = lamp.accent300,
                modifier = Modifier.padding(top = 10.dp),
            )
        } else {
            Text(
                text = when {
                    loosens && appsWindowOpen -> stringResource(R.string.lock_hint_open)
                    loosens -> stringResource(R.string.lock_hint_gated)
                    else -> stringResource(R.string.lock_hint_free)
                },
                style = LampType.metaSmall,
                color = lamp.neutral600,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LampButton(
                text = stringResource(R.string.lock_discard),
                onClick = onDiscard,
                style = LampButtonStyle.Secondary,
                minHeight = 40.dp,
            )
            LampButton(
                text = if (loosens) {
                    stringResource(R.string.lock_accept_one)
                } else {
                    stringResource(R.string.lock_save)
                },
                onClick = onSave,
                enabled = canSave,
                modifier = Modifier.weight(1f),
                minHeight = 40.dp,
            )
        }
    }
}

@Composable
private fun ProtectionRow(item: ProtectionItem) {
    val lamp = LocalLamp.current
    if (item.ok) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhosphorIcon(phosphor(R.drawable.ic_ph_check_circle), null, tint = lamp.accent, size = 17.dp)
            Spacer(Modifier.width(11.dp))
            Text(item.title, style = LampType.rowTitle, color = lamp.text, modifier = Modifier.weight(1f))
            Text(item.okLabel, style = LampType.metaSmall, color = lamp.neutral600)
        }
    } else {
        NoticeBanner(
            icon = phosphor(R.drawable.ic_ph_warning_circle),
            title = item.title,
            body = item.consequence.orEmpty(),
            actionLabel = item.actionLabel,
            onAction = item.onAction,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
}

/** Cap on the diff so the section can't grow without bound — as the old save bar did. */
private const val MAX_LISTED_CHANGES = 6

/**
 * The protection list: everything that has to be true for the lock above it to mean anything.
 *
 * This absorbs what used to be three separate cards on two screens — the setup card, the permission
 * rows and the clock-tamper warning. They belong together because they fail the same way: quietly,
 * outside the app, leaving a blocker that looks fine and blocks nothing.
 *
 * Every failing item states its **consequence**, not its status. "Not granted" tells you nothing;
 * "falling back to kick-to-home — no block screen" tells you what you have lost.
 */
@Composable
fun protectionItems(
    accessibilityEnabled: Boolean,
    overlayGranted: Boolean,
    autoTime: Boolean,
    tamperReason: String?,
    serviceRunning: Boolean,
    rulesWereCorrupt: Boolean,
    signalStale: Boolean,
    debuggable: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenDateSettings: () -> Unit,
): List<ProtectionItem> = buildList {
    add(
        ProtectionItem(
            title = stringResource(R.string.protection_accessibility),
            okLabel = stringResource(R.string.protection_accessibility_on),
            ok = accessibilityEnabled,
            consequence = stringResource(R.string.protection_accessibility_off),
            actionLabel = stringResource(R.string.action_enable),
            onAction = onOpenAccessibility,
        ),
    )
    add(
        ProtectionItem(
            title = stringResource(R.string.protection_overlay),
            okLabel = stringResource(R.string.protection_overlay_on),
            ok = overlayGranted,
            consequence = stringResource(R.string.protection_overlay_off),
            actionLabel = stringResource(R.string.action_grant),
            onAction = onOpenOverlay,
        ),
    )
    add(
        ProtectionItem(
            title = stringResource(R.string.protection_autotime),
            okLabel = stringResource(R.string.protection_autotime_on),
            // The latch counts as a failure of this row rather than as a row of its own: it is
            // caused by this being off, and it is cleared by turning it back on.
            ok = autoTime && tamperReason == null,
            consequence = tamperReason?.let { stringResource(R.string.tamper_banner_body, it) }
                ?: stringResource(R.string.protection_autotime_off),
            actionLabel = stringResource(R.string.action_settings),
            onAction = onOpenDateSettings,
        ),
    )
    add(
        ProtectionItem(
            title = stringResource(R.string.protection_service),
            okLabel = stringResource(R.string.protection_service_on),
            ok = serviceRunning,
            consequence = stringResource(R.string.protection_service_off),
            actionLabel = stringResource(R.string.action_enable),
            onAction = onOpenAccessibility,
        ),
    )
    if (rulesWereCorrupt) {
        // The rules on screen are build defaults, not what was configured — and every app added from
        // the picker is gone. Said out loud because the old behaviour was to do this silently, which
        // reads as "my settings mysteriously reset". See PrefsRuleStore.
        add(
            ProtectionItem(
                title = stringResource(R.string.protection_rules_unreadable),
                okLabel = "",
                ok = false,
                consequence = stringResource(R.string.protection_rules_unreadable_body),
            ),
        )
    }
    if (signalStale) {
        // Reel detection keys off a resource-id inside Instagram, so an Instagram update can break it
        // with no other symptom than reels quietly working again. See SignalCanary.
        add(
            ProtectionItem(
                title = stringResource(R.string.protection_reels_stale),
                okLabel = "",
                ok = false,
                consequence = stringResource(R.string.protection_reels_stale_body),
            ),
        )
    }
    if (debuggable) {
        // Not a permission, but the same class of fact: this build's own data can be edited over
        // ADB, so nothing above it is actually enforced against a determined evening.
        add(
            ProtectionItem(
                title = stringResource(R.string.protection_debug_build),
                okLabel = "",
                ok = false,
                consequence = stringResource(R.string.protection_debug_build_body),
            ),
        )
    }
}
