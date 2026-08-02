package com.appblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.appblock.engine.Schedule
import com.appblock.ui.theme.LampDimens
import com.appblock.ui.theme.LampType
import com.appblock.ui.theme.LocalLamp
import java.time.DayOfWeek

/**
 * The one-line answer to "when is this app openable today". A 7 dp track spanning the **logical**
 * day, 04:00 → 04:00, with the allowed windows painted on it and a hairline marking now.
 *
 * It spans the logical day rather than midnight-to-midnight because that is the day the budget
 * resets on: a strip that started at 00:00 would put the reset three-quarters of the way along and
 * split "tonight" across two strips.
 */
@Composable
fun DayStrip(schedule: Schedule?, dayOfWeek: DayOfWeek, minuteOfDay: Int, modifier: Modifier = Modifier) {
    val lamp = LocalLamp.current
    val segments = dayStripSegments(schedule, dayOfWeek)
    val nowFraction = logicalFraction(minuteOfDay)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(LampDimens.dayStripHeight)
            // No schedule → the whole track is openable. With one → the track is "closed" and the
            // allowed windows are painted back on, so the default reads as freedom and a schedule
            // reads as what it is: carving openings out of a closed day.
            .background(
                if (schedule == null) lamp.accent900 else lamp.neutral900,
                RoundedCornerShape(LampDimens.dayStripRadius),
            ),
    ) {
        val width = maxWidth
        if (schedule != null) {
            for (segment in segments) {
                Box(
                    Modifier
                        .offset(x = width * segment.start)
                        .width(width * (segment.endExclusive - segment.start))
                        .fillMaxHeight()
                        .background(lamp.accent900, RoundedCornerShape(LampDimens.dayStripRadius)),
                )
            }
        }
        // The now marker overhangs the track top and bottom so it stays findable against a painted
        // window — inside the track it would vanish into the accent it sits on.
        Box(
            Modifier
                .offset(x = width * nowFraction, y = (-3).dp)
                .width(1.dp)
                .height(LampDimens.dayStripHeight + 6.dp)
                .background(lamp.accent),
        )
    }
}

/** `04 10 16 22 04` under the strip — the logical day's quarter marks. */
@Composable
fun DayStripHours(modifier: Modifier = Modifier) {
    val lamp = LocalLamp.current
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        for (label in listOf("04", "10", "16", "22", "04")) {
            Text(label, style = LampType.tick.copy(fontFeatureSettings = "tnum"), color = lamp.neutral700)
        }
    }
}

/** A painted span of the strip, as fractions of its width. */
data class StripSegment(val start: Float, val endExclusive: Float)

/**
 * The allowed windows of [dayOfWeek]'s logical day, as fractions of the strip.
 *
 * Two calendar days feed one logical day: 04:00–24:00 comes from [dayOfWeek], and 00:00–04:00 from
 * the day *after* it. Windows are clipped to their half rather than wrapped, because
 * [com.appblock.engine.TimeWindow] is deliberately non-wrapping and an overnight rule is already
 * stored as two windows — so clipping is reassembly, not interpretation.
 */
fun dayStripSegments(schedule: Schedule?, dayOfWeek: DayOfWeek): List<StripSegment> {
    if (schedule == null) return listOf(StripSegment(0f, 1f))
    val out = mutableListOf<StripSegment>()
    for (window in schedule.allowedByDay[dayOfWeek].orEmpty()) {
        val start = window.startMinuteOfDay.coerceAtLeast(DAY_START_MINUTE)
        val end = window.endMinuteOfDay.coerceAtMost(MINUTES_PER_DAY)
        if (end > start) out += StripSegment(logicalFraction(start), logicalFraction(end - 1) + STEP)
    }
    for (window in schedule.allowedByDay[dayOfWeek.plus(1)].orEmpty()) {
        val start = window.startMinuteOfDay.coerceAtLeast(0)
        val end = window.endMinuteOfDay.coerceAtMost(DAY_START_MINUTE)
        if (end > start) out += StripSegment(logicalFraction(start), logicalFraction(end - 1) + STEP)
    }
    return out
}

/** Where a minute-of-day falls along the 04:00 → 04:00 strip, 0f..1f. */
fun logicalFraction(minuteOfDay: Int): Float =
    Math.floorMod(minuteOfDay - DAY_START_MINUTE, MINUTES_PER_DAY) / MINUTES_PER_DAY.toFloat()

private const val DAY_START_MINUTE = 4 * 60
private const val STEP = 1f / MINUTES_PER_DAY
