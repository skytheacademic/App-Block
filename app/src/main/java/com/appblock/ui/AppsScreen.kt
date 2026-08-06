package com.appblock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.appblock.R
import com.appblock.engine.DayLabels
import com.appblock.engine.ExceptionState
import com.appblock.engine.Target
import com.appblock.engine.TargetSettings
import com.appblock.engine.TargetStatus
import com.appblock.engine.TargetSummaries
import com.appblock.ui.theme.LampDimens
import com.appblock.ui.theme.LampType
import com.appblock.ui.theme.LocalLamp
import java.time.LocalDateTime

/**
 * Apps: one card per target, showing the rule in force and today's standing against it.
 *
 * Editing opens the limits sheet; the toggle is the enable/disable, which is a *loosening* when
 * switched off and therefore only lands through the gate on Lock. Nothing on this screen commits —
 * that separation is what lets the sheet keep having no Save of its own.
 */
@Composable
fun AppsScreen(
    rules: RulesDraft,
    statuses: Map<Target, TargetStatus>,
    now: LocalDateTime,
    lockLine: String,
    onEdit: (Target) -> Unit,
    onAdd: () -> Unit,
    onOpenLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lamp = LocalLamp.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = LampDimens.screenPadding, end = LampDimens.screenPadding, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.tab_apps),
                style = LampType.screenTitle,
                color = lamp.text,
                modifier = Modifier.weight(1f),
            )
            LampButton(
                text = stringResource(R.string.action_add),
                onClick = onAdd,
                style = LampButtonStyle.Ghost,
                leadingIcon = phosphor(R.drawable.ic_ph_plus),
                textStyle = LampType.buttonSmall,
                minHeight = 30.dp,
            )
        }
        Text(
            text = stringResource(R.string.apps_sub),
            style = LampType.body.copy(fontSize = LampType.body.fontSize),
            color = lamp.neutral500,
            modifier = Modifier.padding(
                start = LampDimens.screenPadding,
                end = LampDimens.screenPadding,
                top = 6.dp,
            ),
        )

        Column(
            modifier = Modifier.padding(
                start = LampDimens.screenPadding,
                end = LampDimens.screenPadding,
                top = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for ((target, settings) in rules.draft.targets) {
                TargetCard(
                    target = target,
                    settings = settings,
                    status = statuses[target],
                    now = now,
                    windowMinutes = rules.saved.exceptionWindowMinutes,
                    onEdit = { onEdit(target) },
                    onEnabledChange = { rules.setEnabled(target, it) },
                )
            }
        }

        // The lock line lives at the foot of Apps because that is where you find out an edit you
        // just made can't land yet — and it is a route through, not a second control.
        FadingRule(Modifier.padding(top = 18.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenLock)
                .padding(horizontal = LampDimens.screenPadding, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhosphorIcon(phosphor(R.drawable.ic_ph_lock_simple), null, tint = lamp.neutral400, size = 16.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = lockLine,
                style = LampType.meta.copy(fontFeatureSettings = "tnum"),
                color = lamp.neutral500,
                modifier = Modifier.weight(1f),
            )
            PhosphorIcon(phosphor(R.drawable.ic_ph_caret_right), null, tint = lamp.neutral600, size = 14.dp)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TargetCard(
    target: Target,
    settings: TargetSettings,
    status: TargetStatus?,
    now: LocalDateTime,
    windowMinutes: Int,
    onEdit: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    val lamp = LocalLamp.current
    LampCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(iconFor(target), null)
            Spacer(Modifier.width(11.dp))
            // Only the name area opens the sheet: the toggle sits in the same row and a card-wide
            // tap target would swallow it.
            Column(
                modifier = Modifier.weight(1f).clickable(onClick = onEdit),
            ) {
                Text(labelFor(target), style = LampType.rowTitleLarge, color = lamp.text)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = ruleSummary(settings),
                    style = LampType.micro.copy(fontFeatureSettings = "tnum"),
                    color = lamp.neutral600,
                )
            }
            Spacer(Modifier.width(11.dp))
            LampToggle(checked = settings.enabled, onCheckedChange = onEnabledChange)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.today_col_used_cap).uppercase(),
                style = LampType.sectionLabel,
                color = lamp.neutral700,
                modifier = Modifier.alignByBaseline().weight(1f),
            )
            UsedCapCell(
                used = formatHms(status?.usedSeconds ?: 0L),
                cap = formatHms((status?.effectiveCapMinutes ?: 0) * 60L),
                width = LampDimens.cardNumberColumn,
                style = LampType.numberSmall,
                modifier = Modifier.alignByBaseline(),
            )
        }

        // The strip follows the *draft* schedule: you are looking at it while you edit it, and a
        // strip that only moved after a 2-hour window would be useless for authoring.
        DayStrip(
            schedule = settings.schedule,
            dayOfWeek = now.dayOfWeek,
            minuteOfDay = now.hour * 60 + now.minute,
            modifier = Modifier.padding(top = 10.dp),
        )
        DayStripHours()

        val note = cardNote(status, windowMinutes)
        if (note != null) {
            Text(
                text = note,
                style = LampType.micro.copy(fontFeatureSettings = "tnum"),
                color = lamp.accent300,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** "30 min every day · raise to 1 h", or the split form when the two caps disagree. */
@Composable
private fun ruleSummary(settings: TargetSettings): String {
    val summary = TargetSummaries.of(settings)
    // No cap lines at all = a schedule-only target. Its hours are already drawn by the card's day
    // strip, so the subtitle only has to say that there is no cap behind them.
    if (summary.limits.isEmpty()) return stringResource(R.string.apps_rule_hours_only)
    val ceiling = formatWindow(summary.exceptionCeilingMinutes)
    return if (summary.limits.size == 1) {
        stringResource(R.string.apps_rule_every_day, formatWindow(summary.limits[0].minutes), ceiling)
    } else {
        // Two lines means the weekday/weekend split is in play, and that split is fixed in the
        // engine — so the day sets come from DayLabels rather than from a hard-coded "M–F".
        stringResource(
            R.string.apps_rule_split,
            formatWindow(summary.limits[0].minutes),
            DayLabels.of(summary.limits[0].days),
            formatWindow(summary.limits[1].minutes),
            DayLabels.of(summary.limits[1].days),
        )
    }
}

/** The one live fact a card adds beyond its rule: why it is shut right now, or what is coming. */
@Composable
private fun cardNote(status: TargetStatus?, windowMinutes: Int): String? {
    if (status == null) return null
    val exception = status.exception
    if (exception is ExceptionState.Pending) {
        return stringResource(
            R.string.apps_note_exception,
            exception.extraMinutes,
            formatHmsFromMs(status.exceptionActivatesInMs ?: 0L),
            formatWindow(exception.windowMinutes.takeIf { it > 0 } ?: windowMinutes),
        )
    }
    return when {
        status.blockedBySchedule -> stringResource(R.string.apps_note_closed)
        status.remainingSeconds <= 0L -> stringResource(R.string.apps_note_spent, formatResetHour())
        else -> null
    }
}
