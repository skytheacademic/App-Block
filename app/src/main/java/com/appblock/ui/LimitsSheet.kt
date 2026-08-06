package com.appblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.appblock.R
import com.appblock.engine.ChangeDirection
import com.appblock.engine.DayLabels
import com.appblock.engine.Schedule
import com.appblock.engine.ScheduleEditorModel
import com.appblock.engine.Target
import com.appblock.engine.TargetSettings
import com.appblock.engine.WindowRule
import com.appblock.ui.theme.LampDimens
import com.appblock.ui.theme.LampType
import com.appblock.ui.theme.LocalLamp
import java.time.DayOfWeek

/**
 * Where a target's rules are authored: three caps and an optional schedule.
 *
 * It deliberately has **no Save of its own**, and that invariant is inherited unchanged from the
 * current code. [com.appblock.engine.DurableChangeGate] classifies the whole
 * [com.appblock.engine.DurableSettings] at once and a loosening consumes the single-use window, so a
 * second commit point would mean either a second gate implementation or a window spent twice. The
 * sheet edits the shared draft; the Lock tab stays the only place a change lands. All this sheet
 * does about it is say which way the pending edit moves, and where to go to commit it.
 */
@Composable
fun LimitsSheet(
    target: Target,
    settings: TargetSettings,
    direction: ChangeDirection,
    dirty: Boolean,
    onWeekday: (Int) -> Unit,
    onWeekend: (Int) -> Unit,
    onCeiling: (Int) -> Unit,
    onSchedule: (Schedule?) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val lamp = LocalLamp.current
    LampSheet(onDismiss = onDismiss) {
        Text(
            text = stringResource(R.string.limits_title, labelFor(target)),
            style = LampType.sectionTitle,
            color = lamp.text,
        )
        scopeNoteRes(target)?.let { note ->
            Text(
                text = stringResource(note),
                style = LampType.micro,
                color = lamp.neutral600,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // A schedule-only target has no caps, so it gets no cap steppers. Rendering them against its
        // 0-placeholders would put three controls on screen that change a number nothing reads —
        // and worse, the ceiling stepper would imply an exception can buy time here. It can't: the
        // schedule gate runs before the budget, so no exception ever reaches a schedule block.
        if (!settings.scheduleOnly) {
            LampStepperRow(
                label = stringResource(R.string.limits_weekday),
                display = formatWindow(settings.weekdayMinutes),
                onMinus = { onWeekday((settings.weekdayMinutes - CAP_STEP).coerceAtLeast(0)) },
                onPlus = { onWeekday((settings.weekdayMinutes + CAP_STEP).coerceAtMost(MINUTES_PER_DAY)) },
                modifier = Modifier.padding(top = 16.dp),
            )
            LampStepperRow(
                label = stringResource(R.string.limits_weekend),
                display = formatWindow(settings.weekendMinutes),
                onMinus = { onWeekend((settings.weekendMinutes - CAP_STEP).coerceAtLeast(0)) },
                onPlus = { onWeekend((settings.weekendMinutes + CAP_STEP).coerceAtMost(MINUTES_PER_DAY)) },
                modifier = Modifier.padding(top = 8.dp),
            )
            LampStepperRow(
                label = stringResource(R.string.limits_ceiling),
                display = formatWindow(settings.exceptionMaxMinutes),
                onMinus = { onCeiling((settings.exceptionMaxMinutes - CAP_STEP).coerceAtLeast(0)) },
                onPlus = { onCeiling((settings.exceptionMaxMinutes + CAP_STEP).coerceAtMost(MINUTES_PER_DAY)) },
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        ScheduleEditor(
            schedule = settings.schedule,
            onScheduleChange = onSchedule,
            modifier = Modifier.padding(top = 16.dp),
        )

        Text(
            text = when {
                !dirty -> stringResource(R.string.limits_hint_clean)
                direction == ChangeDirection.LOOSEN -> stringResource(R.string.limits_hint_loosen)
                else -> stringResource(R.string.limits_hint_tighten)
            },
            style = LampType.metaSmall,
            color = lamp.neutral600,
            modifier = Modifier.padding(top = 14.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onRemove != null) {
                LampButton(
                    text = stringResource(R.string.limits_remove_app),
                    onClick = onRemove,
                    style = LampButtonStyle.Secondary,
                    minHeight = 40.dp,
                )
            }
            LampButton(
                text = stringResource(R.string.action_done),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                minHeight = 40.dp,
            )
        }
    }
}

/**
 * Schedule authoring on the engine's full per-day model: a list of window rules, each "these days,
 * this From→To range". Stepping To past midnight (so To ≤ From) authors an overnight span in one
 * gesture — [ScheduleEditorModel] compiles it to two engine windows (evening + next-day morning).
 * Extra rules give different hours on different days, or several windows in one day.
 *
 * The model is untouched by the redesign; only its skin changed.
 */
@Composable
private fun ScheduleEditor(
    schedule: Schedule?,
    onScheduleChange: (Schedule?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lamp = LocalLamp.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.schedule_toggle),
            style = LampType.rowTitle,
            color = lamp.text,
            modifier = Modifier.weight(1f),
        )
        LampToggle(
            checked = schedule != null,
            onCheckedChange = { on ->
                onScheduleChange(if (on) ScheduleEditorModel.toSchedule(listOf(DEFAULT_WINDOW_RULE)) else null)
            },
        )
    }

    if (schedule != null) {
        // Local authoring state so a half-edited rule (say, no days picked yet — it compiles to
        // nothing) survives recomposition; re-derived only when the schedule changed underneath us
        // (Discard, the toggle, an external edit).
        var rules by remember { mutableStateOf(ScheduleEditorModel.decompose(schedule)) }
        if (ScheduleEditorModel.toSchedule(rules) != schedule) {
            rules = ScheduleEditorModel.decompose(schedule)
        }

        fun update(newRules: List<WindowRule>) {
            rules = newRules
            onScheduleChange(ScheduleEditorModel.toSchedule(newRules))
        }

        rules.forEachIndexed { i, rule ->
            WindowRuleEditor(
                rule = rule,
                showRemove = rules.size > 1,
                onChange = { changed -> update(rules.toMutableList().also { it[i] = changed }) },
                onRemove = { update(rules.filterIndexed { j, _ -> j != i }) },
            )
        }
        LampButton(
            text = stringResource(R.string.schedule_add),
            onClick = { update(rules + DEFAULT_WINDOW_RULE) },
            style = LampButtonStyle.Ghost,
            textStyle = LampType.buttonSmall,
            minHeight = 34.dp,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.schedule_explainer),
            style = LampType.micro,
            color = lamp.neutral600,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** One window rule: its day chips, a wrapping From/To range, and (if removable) a remove action. */
@Composable
private fun WindowRuleEditor(
    rule: WindowRule,
    showRemove: Boolean,
    onChange: (WindowRule) -> Unit,
    onRemove: () -> Unit,
) {
    val lamp = LocalLamp.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (day in DayOfWeek.entries) {
            DayChip(
                label = DayLabels.short(day),
                selected = day in rule.days,
                onClick = {
                    val days = if (day in rule.days) rule.days - day else rule.days + day
                    onChange(rule.copy(days = days))
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (rule.days.isEmpty()) {
        Text(
            text = stringResource(R.string.schedule_no_days),
            style = LampType.micro,
            color = lamp.accent300,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    LampStepperRow(
        label = stringResource(R.string.schedule_from),
        display = formatHm(rule.startMin),
        onMinus = { onChange(rule.copy(startMin = ScheduleEditorModel.stepClock(rule.startMin, -CLOCK_STEP, rule.endMin))) },
        onPlus = { onChange(rule.copy(startMin = ScheduleEditorModel.stepClock(rule.startMin, +CLOCK_STEP, rule.endMin))) },
        modifier = Modifier.padding(top = 10.dp),
    )
    LampStepperRow(
        label = stringResource(R.string.schedule_to),
        display = formatHm(rule.endMin),
        onMinus = { onChange(rule.copy(endMin = ScheduleEditorModel.stepClock(rule.endMin, -CLOCK_STEP, rule.startMin))) },
        onPlus = { onChange(rule.copy(endMin = ScheduleEditorModel.stepClock(rule.endMin, +CLOCK_STEP, rule.startMin))) },
        modifier = Modifier.padding(top = 6.dp),
    )
    if (rule.overnight) {
        Text(
            text = stringResource(R.string.schedule_overnight, formatHm(rule.startMin), formatHm(rule.endMin)),
            style = LampType.micro.copy(fontFeatureSettings = "tnum"),
            color = lamp.accent300,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    if (showRemove) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LampButton(
                text = stringResource(R.string.schedule_remove),
                onClick = onRemove,
                style = LampButtonStyle.Ghost,
                textStyle = LampType.buttonSmall,
                minHeight = 32.dp,
            )
        }
    }
}

/**
 * A day chip. Height only — the width comes from the caller's `weight(1f)`, so seven chips always
 * divide the row exactly instead of overflowing once the labels went from one letter to two.
 */
@Composable
private fun DayChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val lamp = LocalLamp.current
    Box(
        modifier = modifier
            .height(LampDimens.stepperButton)
            .background(
                if (selected) lamp.accent900 else lamp.neutral900,
                RoundedCornerShape(LampDimens.iconTileRadius),
            )
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = LampType.metaSmall,
            color = if (selected) lamp.accent300 else lamp.neutral600,
        )
    }
}

/** Caps move in 5-minute steps; clock ranges in 30-minute ones. */
private const val CAP_STEP = 5
private const val CLOCK_STEP = 30

/** The starter rule when a schedule is first toggled on: every day, 18:00–20:00. */
private val DEFAULT_WINDOW_RULE = WindowRule(DayOfWeek.entries.toSet(), 18 * 60, 20 * 60)
