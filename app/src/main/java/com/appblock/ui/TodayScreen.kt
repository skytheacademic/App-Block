package com.appblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appblock.R
import com.appblock.engine.Access
import com.appblock.engine.Availability
import com.appblock.engine.DayBoundary
import com.appblock.engine.DayLabels
import com.appblock.engine.DayType
import com.appblock.engine.DayUsage
import com.appblock.engine.DurableSettings
import com.appblock.engine.ExceptionState
import com.appblock.engine.Target
import com.appblock.engine.TargetStatus
import com.appblock.engine.TargetSummaries
import com.appblock.ui.theme.LampDimens
import com.appblock.ui.theme.LampType
import com.appblock.ui.theme.LocalLamp
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * One row of the Today table. Pre-resolved so the composable does no engine work while it draws —
 * the screen re-renders every second and the numbers must be computed once per tick, not per row.
 */
data class TodayRow(
    val target: Target,
    val usedSeconds: Long,
    /** The *effective* cap: raised while an exception is active. */
    val capSeconds: Long,
    val state: TargetState,
    /** Extra minutes while an exception is active, else null — the `+5 min` chip. */
    val raisedMinutes: Int?,
    /** Ms until a pending exception activates, else null. */
    val exceptionPendingInMs: Long?,
    val exceptionExtraMinutes: Int?,
    /** The allowed window covering today, when a schedule is why this target is closed. */
    val scheduleWindow: Pair<Int, Int>?,
)

/** What the chip says. Every one of these is a fact about the rules, never an error. */
enum class TargetState { OPEN, SPENT, CLOSED, OFF }

/**
 * Today: "how am I doing" in one glance, and the only place an exception starts.
 *
 * The per-app "time left" column is deliberately gone. Three apps each reporting their own remainder
 * is three numbers to add up before you know the answer to the question you actually opened the app
 * with; the hero and the total row carry the remaining time now, and the table answers the narrower
 * question of where it went.
 */
@Composable
fun TodayScreen(
    now: LocalDateTime,
    logicalDay: LocalDate,
    rows: List<TodayRow>,
    /** Σ remaining seconds over enabled targets — the hero. */
    remainingSeconds: Long,
    usedMinutes: Int,
    capMinutes: Int,
    closedCount: Int,
    exception: TodayException?,
    tamperReason: String?,
    /** One row per target of the last seven logical days. Empty until a rollover has happened. */
    week: List<WeekRow>,
    onCancelException: () -> Unit,
    onRequestMoreTime: () -> Unit,
    onOpenLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lamp = LocalLamp.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Header: the *logical* day, not the calendar one — at 02:00 this still says yesterday,
        // because that is the day whose budget is still running.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = LampDimens.screenPadding, end = LampDimens.screenPadding, top = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.tab_today),
                style = LampType.screenTitle,
                color = lamp.text,
                modifier = Modifier.alignByBaseline().weight(1f),
            )
            Text(
                text = "${formatLogicalDay(logicalDay)} · ${formatClock(now)}",
                style = LampType.micro,
                color = lamp.neutral600,
                modifier = Modifier.alignByBaseline(),
            )
        }

        // Hero.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = LampDimens.screenPadding, end = LampDimens.screenPadding, top = 22.dp),
        ) {
            Text(
                text = formatHms(remainingSeconds),
                style = LampType.hero,
                color = lamp.text,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.today_hero_caption),
                style = LampType.body,
                color = lamp.neutral500,
                modifier = Modifier.alignByBaseline().padding(bottom = 5.dp),
            )
        }
        Text(
            text = stringResource(
                R.string.today_hero_sub,
                usedMinutes,
                capMinutes,
                closedCount,
                rows.size,
            ),
            style = LampType.meta,
            color = lamp.neutral600,
            modifier = Modifier.padding(
                start = LampDimens.screenPadding,
                end = LampDimens.screenPadding,
                top = 8.dp,
            ),
        )

        // The tamper latch is the one thing that overrides every rule on this screen, so it says so
        // here as well as on Lock — the numbers below are true but irrelevant while it is set.
        if (tamperReason != null) {
            NoticeBanner(
                icon = phosphor(R.drawable.ic_ph_warning_circle),
                title = stringResource(R.string.tamper_banner_title),
                body = stringResource(R.string.tamper_banner_body, tamperReason),
                actionLabel = stringResource(R.string.action_open_lock),
                onAction = onOpenLock,
                modifier = Modifier.padding(
                    start = LampDimens.screenPadding,
                    end = LampDimens.screenPadding,
                    top = 18.dp,
                ),
            )
        }

        if (exception != null) {
            ExceptionBanner(
                exception = exception,
                now = now,
                onCancel = onCancelException,
                modifier = Modifier.padding(
                    start = LampDimens.screenPadding,
                    end = LampDimens.screenPadding,
                    top = 18.dp,
                ),
            )
        }

        // The table.
        Column(
            Modifier.padding(
                start = LampDimens.screenPadding,
                end = LampDimens.screenPadding,
                top = 20.dp,
            ),
        ) {
            TableHeader(
                stringResource(R.string.today_col_target),
                stringResource(R.string.today_col_used_cap) to LampDimens.numberColumn,
            )
            TableRule()
            for (row in rows) {
                TodayTableRow(row, now)
                RowRule()
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
                Text(
                    text = stringResource(R.string.today_total),
                    style = LampType.meta,
                    color = lamp.neutral500,
                    modifier = Modifier.weight(1f),
                )
                UsedCapCell(
                    used = formatHms(rows.filter { it.state != TargetState.OFF }.sumOf { it.usedSeconds }),
                    cap = formatHms(rows.filter { it.state != TargetState.OFF }.sumOf { it.capSeconds }),
                    width = LampDimens.numberColumn,
                    style = LampType.meta.copy(fontFeatureSettings = "tnum"),
                )
            }
        }

        if (week.isNotEmpty()) {
            WeekStrip(
                week = week,
                logicalDay = logicalDay,
                modifier = Modifier.padding(
                    start = LampDimens.screenPadding,
                    end = LampDimens.screenPadding,
                    top = 16.dp,
                ),
            )
        }

        // Global, not per-card: an exception belongs to the moment you want more time, not to a card
        // you happen to be looking at — which is why the picker sheet exists at all.
        Box(
            Modifier.padding(
                start = LampDimens.screenPadding,
                end = LampDimens.screenPadding,
                top = 18.dp,
                bottom = 20.dp,
            ),
        ) {
            LampButton(
                text = stringResource(R.string.today_request_more),
                onClick = onRequestMoreTime,
                leadingIcon = phosphor(R.drawable.ic_ph_plus_circle),
                modifier = Modifier.fillMaxWidth(),
                minHeight = 42.dp,
            )
        }
    }
}

@Composable
private fun TodayTableRow(row: TodayRow, now: LocalDateTime) {
    val lamp = LocalLamp.current
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            PhosphorIcon(
                painter = iconFor(row.target),
                contentDescription = null,
                tint = lamp.neutral400,
                size = 16.dp,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = shortLabelFor(row.target),
                style = LampType.rowTitle,
                color = lamp.text,
                modifier = Modifier.alignByBaseline(),
            )
            chipFor(row.state)?.let { chip ->
                Spacer(Modifier.width(10.dp))
                StateChip(stringResource(chip), Modifier.align(Alignment.CenterVertically))
            }
            row.raisedMinutes?.let { extra ->
                Spacer(Modifier.width(6.dp))
                RaisedChip(
                    stringResource(R.string.chip_raised, extra),
                    Modifier.align(Alignment.CenterVertically),
                )
            }
            Spacer(Modifier.weight(1f))
            // Baseline-aligned with the name, not centred on the row: a row with a sub-line must not
            // push its number down out of line with the rows above it.
            UsedCapCell(
                used = formatHms(row.usedSeconds),
                cap = formatHms(row.capSeconds),
                width = LampDimens.numberColumn,
                modifier = Modifier.alignByBaseline(),
            )
        }
        val sub = subLineFor(row, now)
        if (sub != null) {
            Text(
                text = sub.first,
                style = LampType.micro,
                color = sub.second,
                modifier = Modifier.padding(start = 26.dp, top = 3.dp),
            )
        }
    }
}

private fun chipFor(state: TargetState): Int? = when (state) {
    TargetState.SPENT -> R.string.chip_blocked
    TargetState.CLOSED -> R.string.chip_closed
    TargetState.OFF -> R.string.chip_off
    TargetState.OPEN -> null
}

/** The one line of context a row gets: when it comes back, or when its exception lands. */
@Composable
private fun subLineFor(row: TodayRow, now: LocalDateTime): Pair<String, Color>? {
    val lamp = LocalLamp.current
    val pendingMs = row.exceptionPendingInMs
    if (pendingMs != null && row.exceptionExtraMinutes != null) {
        return stringResource(
            R.string.today_sub_exception_pending,
            row.exceptionExtraMinutes,
            formatClockIn(now, pendingMs),
        ) to lamp.accent300
    }
    return when (row.state) {
        TargetState.SPENT ->
            stringResource(R.string.today_sub_reopens, formatResetHour()) to lamp.neutral600
        TargetState.CLOSED -> row.scheduleWindow?.let { (start, end) ->
            stringResource(R.string.today_sub_outside, formatHm(start), formatHm(end)) to lamp.neutral600
        }
        else -> null
    }
}

/** One target's week: seven days oldest-first, each as minutes used against that day's cap. */
data class WeekRow(val target: Target, val days: List<WeekDay>)

/** One bar. [capMinutes] 0 means the app was blocked outright that day. */
data class WeekDay(val date: LocalDate, val minutesUsed: Int, val capMinutes: Int) {
    val atCap: Boolean get() = capMinutes > 0 && minutesUsed >= capMinutes
    val fraction: Float
        get() = when {
            capMinutes <= 0 -> if (minutesUsed > 0) 1f else 0f
            else -> (minutesUsed.toFloat() / capMinutes).coerceIn(0f, 1f)
        }
}

/**
 * The last seven days, one row of bars per target.
 *
 * Bar height is minutes used over that day's cap, so the rows are comparable even though the caps
 * are not — a full bar means "spent", whatever the number behind it was. A day at the cap is
 * `accent-500`, under it `accent-700`, and a zero day is a 2 dp stub rather than nothing at all,
 * because an absent bar and a zero bar mean different things and only one of them is true.
 */
@Composable
private fun WeekStrip(week: List<WeekRow>, logicalDay: LocalDate, modifier: Modifier = Modifier) {
    val lamp = LocalLamp.current
    Column(modifier) {
        SectionLabel(stringResource(R.string.today_week_label), Modifier.padding(bottom = 12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            for (row in week) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = shortLabelFor(row.target),
                        style = LampType.metaSmall,
                        color = lamp.neutral400,
                        maxLines = 1,
                        modifier = Modifier.width(74.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Row(
                        modifier = Modifier.weight(1f).height(26.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        for (day in row.days) {
                            val today = day.date == logicalDay
                            val zero = day.minutesUsed <= 0
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(if (zero) 2.dp else (26.dp * day.fraction).coerceAtLeast(2.dp))
                                    .background(
                                        when {
                                            zero -> lamp.neutral800
                                            today -> lamp.accent
                                            day.atCap -> lamp.accent500
                                            else -> lamp.accent700
                                        },
                                    ),
                            )
                        }
                    }
                }
            }
            Row {
                Spacer(Modifier.width(84.dp))
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    for (day in week.first().days) {
                        val today = day.date == logicalDay
                        Text(
                            text = DayLabels.short(day.date.dayOfWeek),
                            style = LampType.dayLetter,
                            color = if (today) lamp.accent300 else lamp.neutral700,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** The Today banner's view of an exception: which app, which phase, and how long is left. */
data class TodayException(
    val target: Target,
    val extraMinutes: Int,
    val active: Boolean,
    /** Pending: ms until it starts. Active: ms until it ends. */
    val remainingMs: Long,
    /** The cap the raise lands on, for the pending line. */
    val raisedCapMinutes: Int,
    val windowMinutes: Int,
)

@Composable
private fun ExceptionBanner(
    exception: TodayException,
    now: LocalDateTime,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lamp = LocalLamp.current
    val name = shortLabelFor(exception.target)
    LampCard(modifier, padding = PaddingValues(horizontal = 13.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhosphorIcon(
                // Filled while the raise is live, outlined while it is only promised — the same
                // "state is live" distinction the tab bar uses.
                painter = phosphor(
                    if (exception.active) R.drawable.ic_ph_hourglass_high_fill
                    else R.drawable.ic_ph_hourglass_medium,
                ),
                contentDescription = null,
                tint = lamp.accent,
                size = 18.dp,
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (exception.active) {
                        stringResource(
                            R.string.exception_banner_active,
                            name,
                            exception.extraMinutes,
                            formatClockIn(now, exception.remainingMs),
                        )
                    } else {
                        stringResource(
                            R.string.exception_banner_pending,
                            name,
                            exception.extraMinutes,
                            formatHmsFromMs(exception.remainingMs),
                        )
                    },
                    style = LampType.body.copy(fontFeatureSettings = "tnum"),
                    color = lamp.text,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (exception.active) {
                        stringResource(
                            R.string.exception_banner_active_sub,
                            formatHmsFromMs(exception.remainingMs),
                        )
                    } else {
                        stringResource(
                            R.string.exception_banner_pending_sub,
                            formatWindow(exception.windowMinutes),
                            exception.raisedCapMinutes,
                        )
                    },
                    style = LampType.micro,
                    color = lamp.neutral500,
                )
            }
            Spacer(Modifier.width(10.dp))
            LampButton(
                text = stringResource(R.string.action_cancel),
                onClick = onCancel,
                style = LampButtonStyle.Secondary,
                textStyle = LampType.buttonSmall,
                minHeight = 32.dp,
            )
        }
    }
}

/**
 * A card that states a problem and offers the one action that fixes it. Accent, never red: a lapsed
 * permission is a fact about the phone, and the design has no error colour on screen.
 */
@Composable
fun NoticeBanner(
    icon: Painter,
    title: String,
    body: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val lamp = LocalLamp.current
    LampCard(modifier, padding = PaddingValues(horizontal = 13.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            PhosphorIcon(icon, null, tint = lamp.accent, size = 18.dp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = LampType.body, color = lamp.text)
                Spacer(Modifier.height(2.dp))
                Text(body, style = LampType.micro, color = lamp.neutral500)
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.width(10.dp))
                LampButton(
                    text = actionLabel,
                    onClick = onAction,
                    style = LampButtonStyle.Secondary,
                    textStyle = LampType.buttonSmall,
                    minHeight = 32.dp,
                )
            }
        }
    }
}

// ---- state assembly -------------------------------------------------------------------------

/**
 * Turn a coordinator snapshot plus the saved settings into the rows Today draws.
 *
 * Both sources are needed: `snapshot()` covers only *enabled* targets (disabled ones are omitted
 * from `toRules()`, which is what "off = unenforced" means in the engine), but the table still lists
 * a switched-off app with an `off` chip — otherwise turning an app off makes it vanish, and a
 * commitment device should never let a loosening hide its own effect.
 */
fun todayRows(
    settings: DurableSettings,
    statuses: List<TargetStatus>,
    usageSecondsFor: (Target) -> Long,
    today: LocalDate,
    todayDayOfWeek: java.time.DayOfWeek,
): List<TodayRow> {
    val byTarget = statuses.associateBy { it.target }
    return settings.targets.entries.map { (target, targetSettings) ->
        val status = byTarget[target]
        if (status == null || !targetSettings.enabled) {
            val usage = TargetSummaries.todayUsage(targetSettings, null, today)
            return@map TodayRow(
                target = target,
                usedSeconds = usageSecondsFor(target),
                capSeconds = usage.capMinutes * 60L,
                state = TargetState.OFF,
                raisedMinutes = null,
                exceptionPendingInMs = null,
                exceptionExtraMinutes = null,
                scheduleWindow = null,
            )
        }
        val exception = status.exception
        val state = when {
            status.blockedBySchedule -> TargetState.CLOSED
            status.remainingSeconds <= 0L || status.access == Access.BLOCK -> TargetState.SPENT
            else -> TargetState.OPEN
        }
        TodayRow(
            target = target,
            usedSeconds = status.usedSeconds,
            capSeconds = status.effectiveCapMinutes * 60L,
            state = state,
            raisedMinutes = (exception as? ExceptionState.Active)?.extraMinutes,
            exceptionPendingInMs = (exception as? ExceptionState.Pending)?.let { status.exceptionActivatesInMs },
            exceptionExtraMinutes = (exception as? ExceptionState.Pending)?.extraMinutes,
            scheduleWindow = todaysWindow(targetSettings, todayDayOfWeek),
        )
    }
}

/**
 * Assemble the week strip: seven logical days ending today, per enabled target.
 *
 * Today's bar comes from the live counter, the six before it from the archive. A day with no
 * archived entry is a real zero — the archive is written when the counter rolls over, so a missing
 * day is a day the app was never opened, not a day whose record was lost.
 *
 * Returns empty until at least one archived day exists, so a fresh install shows no strip rather
 * than a week of flat stubs claiming six days of perfect restraint.
 */
fun weekRows(
    settings: DurableSettings,
    today: LocalDate,
    historyFor: (Target) -> List<DayUsage>,
    todaySecondsFor: (Target) -> Long,
): List<WeekRow> {
    val days = (WEEK_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
    var anyHistory = false
    val rows = settings.targets.entries
        .filter { it.value.enabled }
        .map { (target, targetSettings) ->
            val archived = historyFor(target).associateBy { it.day }
            if (archived.isNotEmpty()) anyHistory = true
            WeekRow(
                target = target,
                days = days.map { date ->
                    val seconds = if (date == today) {
                        todaySecondsFor(target)
                    } else {
                        archived[date]?.secondsUsed ?: 0L
                    }
                    WeekDay(
                        date = date,
                        minutesUsed = (seconds / 60L).toInt(),
                        // Each day is judged against *that* day's cap, weekday or weekend, so a
                        // Saturday bar isn't measured against a Tuesday's allowance.
                        capMinutes = when (DayBoundary.dayType(date)) {
                            DayType.WEEKEND -> targetSettings.weekendMinutes
                            DayType.WEEKDAY -> targetSettings.weekdayMinutes
                        },
                    )
                },
            )
        }
    return if (anyHistory) rows else emptyList()
}

private const val WEEK_DAYS = 7

/** The allowed window covering [day], for the "outside 18:00–20:00" sub-line. */
private fun todaysWindow(
    settings: com.appblock.engine.TargetSettings,
    day: java.time.DayOfWeek,
): Pair<Int, Int>? =
    TargetSummaries.of(settings).availability
        .filterIsInstance<Availability.Window>()
        .firstOrNull { day in it.days }
        ?.let { it.startMin to it.endMin }
