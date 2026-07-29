package com.appblock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appblock.R
import com.appblock.engine.Target
import com.appblock.ui.theme.LampType
import com.appblock.ui.theme.LocalLamp
import java.time.LocalDateTime

/**
 * The exception flow (CONSTRAINTS.md §5): pick the app, size the raise, then wait.
 *
 * Two sheets rather than one dialog because the two questions are different in kind — *which* app,
 * then *how much* — and because the raise is now started from Today rather than from a per-app card,
 * so there is no app implied by where you tapped.
 *
 * Nothing here decides the window length. That is a durable pre-set
 * ([com.appblock.engine.DurableSettings.exceptionWindowMinutes], default 60) which only the gated
 * settings path can change; the sheet reads it and states it.
 */

/** One row of the picker: what this app's exception could do, or why it can't have one right now. */
data class ExceptionCandidate(
    val target: Target,
    val capMinutes: Int,
    val ceilingMinutes: Int,
    /** "pending" / "active" when one is already running — such a row is shown but not selectable. */
    val existingPhase: ExceptionPhase?,
) {
    /** No headroom between today's cap and the ceiling means an exception could not raise anything. */
    val hasHeadroom: Boolean get() = ceilingMinutes > capMinutes
}

enum class ExceptionPhase { PENDING, ACTIVE }

@Composable
fun ExceptionPickSheet(
    candidates: List<ExceptionCandidate>,
    onPick: (ExceptionCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    val lamp = LocalLamp.current
    LampSheet(onDismiss = onDismiss) {
        Text(
            text = stringResource(R.string.exception_pick_title),
            style = LampType.sectionTitle,
            color = lamp.text,
        )
        Text(
            text = stringResource(R.string.exception_pick_body),
            style = LampType.meta,
            color = lamp.neutral500,
            modifier = Modifier.padding(top = 4.dp),
        )
        for (candidate in candidates) {
            val blocked = candidate.existingPhase != null || !candidate.hasHeadroom
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !blocked) { onPick(candidate) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(iconFor(candidate.target), null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = labelFor(candidate.target),
                        style = LampType.rowTitleLarge,
                        color = lamp.text,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when {
                            candidate.existingPhase == ExceptionPhase.PENDING ->
                                stringResource(R.string.exception_pick_already_pending)
                            candidate.existingPhase == ExceptionPhase.ACTIVE ->
                                stringResource(R.string.exception_pick_already_active)
                            !candidate.hasHeadroom ->
                                stringResource(R.string.exception_pick_no_headroom)
                            else -> stringResource(
                                R.string.exception_pick_sub,
                                formatWindow(candidate.capMinutes),
                                formatWindow(candidate.ceilingMinutes),
                            )
                        },
                        style = LampType.micro.copy(fontFeatureSettings = "tnum"),
                        color = if (blocked) lamp.accent300 else lamp.neutral600,
                    )
                }
                if (!blocked) {
                    PhosphorIcon(
                        painter = phosphor(R.drawable.ic_ph_caret_right),
                        contentDescription = null,
                        tint = lamp.neutral600,
                        size = 15.dp,
                    )
                }
            }
            RowRule()
        }
        LampButton(
            text = stringResource(R.string.action_cancel),
            onClick = onDismiss,
            style = LampButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            minHeight = 40.dp,
        )
    }
}

/**
 * Size the raise. The stepper is clamped to the app's own ceiling — an exception can raise today's
 * cap, never the ceiling itself, which is the whole point of having two numbers.
 */
@Composable
fun ExceptionAmountSheet(
    candidate: ExceptionCandidate,
    /** The durable pre-set: how long the raised cap lasts once it starts. */
    windowMinutes: Int,
    /** The wait before it starts. Read from the build's own setting, never written as "1 hour". */
    waitMs: Long,
    now: LocalDateTime,
    onConfirm: (extraMinutes: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val lamp = LocalLamp.current
    val maxExtra = (candidate.ceilingMinutes - candidate.capMinutes).coerceAtLeast(EXTRA_STEP)
    var extra by remember(candidate.target) { mutableIntStateOf(EXTRA_STEP.coerceAtMost(maxExtra)) }
    val raisedCap = (candidate.capMinutes + extra).coerceAtMost(candidate.ceilingMinutes)
    val waitLabel = formatWindow((waitMs / 60_000L).toInt().coerceAtLeast(1))

    LampSheet(onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhosphorIcon(iconFor(candidate.target), null, tint = lamp.neutral300, size = 17.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.exception_amount_title, shortLabelFor(candidate.target)),
                style = LampType.sectionTitle,
                color = lamp.text,
            )
        }

        LampStepperRow(
            label = stringResource(R.string.exception_extra_label),
            display = stringResource(R.string.exception_extra_value, extra),
            onMinus = { extra = (extra - EXTRA_STEP).coerceAtLeast(EXTRA_STEP) },
            onPlus = { extra = (extra + EXTRA_STEP).coerceAtMost(maxExtra) },
            modifier = Modifier.padding(top = 20.dp),
            buttonWidth = 48.dp,
            valueWidth = 74.dp,
            valueStyle = LampType.rowTitleLarge.copy(fontSize = 16.sp),
        )
        Text(
            text = stringResource(
                R.string.exception_extra_hint,
                formatWindow(candidate.ceilingMinutes),
            ),
            style = LampType.metaSmall,
            color = lamp.neutral600,
            modifier = Modifier.padding(top = 6.dp),
        )

        TableRule(Modifier.padding(top = 18.dp, bottom = 4.dp))
        // The cap row states old → new with the new value in accent-300, so the thing that changes
        // is the thing that is marked.
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.exception_fact_cap),
                style = LampType.body,
                color = lamp.neutral500,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = lamp.text)) { append("${candidate.capMinutes} → ") }
                    withStyle(SpanStyle(color = lamp.accent300)) { append("$raisedCap min") }
                },
                style = LampType.body.copy(fontFeatureSettings = "tnum"),
            )
        }
        RowRule()
        FactRow(
            label = stringResource(R.string.exception_fact_lasts),
            value = formatWindow(windowMinutes),
            valueStyle = LampType.body.copy(fontFeatureSettings = "tnum"),
        )
        RowRule()
        FactRow(
            label = stringResource(R.string.exception_fact_starts),
            value = stringResource(
                R.string.exception_fact_starts_value,
                waitLabel,
                formatClockIn(now, waitMs),
            ),
            valueStyle = LampType.body.copy(fontFeatureSettings = "tnum"),
        )
        Text(
            text = stringResource(R.string.exception_blocks_stay_on),
            style = LampType.metaSmall,
            color = lamp.neutral600,
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LampButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                style = LampButtonStyle.Secondary,
                minHeight = 40.dp,
            )
            LampButton(
                text = stringResource(R.string.exception_start_wait, waitLabel),
                onClick = { onConfirm(extra) },
                modifier = Modifier.weight(1f),
                minHeight = 40.dp,
            )
        }
    }
}

/** Exceptions move in 5-minute steps, matching the caps they raise. */
const val EXTRA_STEP = 5
