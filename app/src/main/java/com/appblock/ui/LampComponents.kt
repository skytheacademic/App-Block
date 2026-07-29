package com.appblock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appblock.stepperTag
import com.appblock.ui.theme.Inter
import com.appblock.ui.theme.LampDimens
import com.appblock.ui.theme.LampType
import com.appblock.ui.theme.LocalLamp
import com.appblock.ui.theme.fadingRuleBrush

/**
 * The pieces every Lamp screen is assembled from. Built by hand rather than by restyling Material
 * components because almost every one of them is a *line* — a 1 px rule that fades at both ends, a
 * 1 px button outline on transparent, a 1 px now-marker — and Material's components are built around
 * fills and elevation. Restyling those to disappear costs more code than drawing the line.
 *
 * Sizes are the handoff's, where CSS px map 1:1 to dp.
 */

// ---- text ---------------------------------------------------------------------------------

/** "target", "used / cap", "loosening costs" — the uppercase tracked label above a section. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = LampType.sectionLabel,
        color = LocalLamp.current.neutral600,
        modifier = modifier,
    )
}

/** "Apps window · open" — the accent kicker over a countdown. */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = LampType.kicker,
        color = LocalLamp.current.accent,
        modifier = modifier,
    )
}

// ---- rules --------------------------------------------------------------------------------

/**
 * A section rule that fades at both ends. Section rules are punctuation between bands of a screen,
 * so they stop short of the edges; table rules ([RowRule]) are structure, so they run the full width.
 */
@Composable
fun FadingRule(modifier: Modifier = Modifier) {
    val lamp = LocalLamp.current
    val insetPx = with(LocalDensity.current) { LampDimens.ruleFadeInset.toPx() }
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawRect(fadingRuleBrush(lamp.divider, size.width, insetPx))
    }
}

/** Between table rows: solid `text @ 8%`. */
@Composable
fun RowRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalLamp.current.rowRule))
}

/** Opens and closes a table — the divider, held back so it doesn't outweigh the rows inside. */
@Composable
fun TableRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalLamp.current.divider.copy(alpha = 0.096f)))
}

// ---- containers ---------------------------------------------------------------------------

/** Card / banner: 8 dp radius, 13 dp padding, `surface` fill, no shadow. */
@Composable
fun LampCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(LampDimens.cardPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(LocalLamp.current.surface, RoundedCornerShape(LampDimens.cardRadius))
            .padding(padding),
        content = content,
    )
}

/** The 32 dp app-identity tile. Its glyph is a neutral mark, not a brand logo, on purpose. */
@Composable
fun IconTile(painter: Painter, contentDescription: String?, modifier: Modifier = Modifier) {
    val lamp = LocalLamp.current
    Box(
        modifier = modifier
            .size(LampDimens.iconTile)
            .background(lamp.neutral900, RoundedCornerShape(LampDimens.iconTileRadius)),
        contentAlignment = Alignment.Center,
    ) {
        PhosphorIcon(painter, contentDescription, tint = lamp.neutral300, size = 16.dp)
    }
}

// ---- icons --------------------------------------------------------------------------------

/**
 * A vendored Phosphor glyph. Material's `Icon` would do, but it defaults to `LocalContentColor`, and
 * here nearly every glyph is a deliberate ramp step rather than the inherited colour — so the tint
 * is required rather than easy to forget.
 */
@Composable
fun PhosphorIcon(
    painter: Painter,
    contentDescription: String?,
    tint: Color,
    size: Dp = 16.dp,
    modifier: Modifier = Modifier,
) {
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}

@Composable
fun phosphor(id: Int): Painter = painterResource(id)

// ---- chips --------------------------------------------------------------------------------

private val ChipText = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 9.5.sp)

/** "blocked", "closed", "off" — facts about the rules, so neutral. Never red. */
@Composable
fun StateChip(text: String, modifier: Modifier = Modifier) {
    val lamp = LocalLamp.current
    Text(
        text = text,
        style = ChipText,
        color = lamp.neutral200,
        modifier = modifier
            .background(lamp.neutral800, RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** "+5 min" — the one chip that carries the accent, because a raised cap is live state. */
@Composable
fun RaisedChip(text: String, modifier: Modifier = Modifier) {
    val lamp = LocalLamp.current
    Text(
        text = text,
        style = ChipText,
        color = lamp.accent100,
        modifier = modifier
            .background(lamp.accent800, RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ---- buttons ------------------------------------------------------------------------------

enum class LampButtonStyle { Primary, Secondary, Ghost }

/**
 * The one button. Primary is a 1 px accent outline on transparent — **never a solid accent fill**;
 * the accent is a line, a mark and a bar in this design, and a filled primary would be the largest
 * block of it on any screen.
 */
@Composable
fun LampButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: LampButtonStyle = LampButtonStyle.Primary,
    leadingIcon: Painter? = null,
    enabled: Boolean = true,
    minHeight: Dp = 36.dp,
    textStyle: TextStyle = LampType.button,
    contentPadding: PaddingValues = PaddingValues(horizontal = 13.dp, vertical = 6.dp),
) {
    val lamp = LocalLamp.current
    val content = when (style) {
        LampButtonStyle.Primary -> lamp.accent
        LampButtonStyle.Secondary -> lamp.text
        LampButtonStyle.Ghost -> lamp.accent
    }
    val border = when (style) {
        LampButtonStyle.Primary -> lamp.accent
        LampButtonStyle.Secondary -> lamp.divider
        LampButtonStyle.Ghost -> Color.Transparent
    }
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = minHeight)
            .border(1.dp, border.copy(alpha = border.alpha * alpha), RoundedCornerShape(LampDimens.cardRadius))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            PhosphorIcon(leadingIcon, null, tint = content.copy(alpha = alpha), size = 15.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text = text, style = textStyle, color = content.copy(alpha = alpha))
    }
}

// ---- toggle -------------------------------------------------------------------------------

/**
 * 38 × 22 dp. On is the only filled accent surface in the design, and its knob is the *ground*
 * colour rather than white, so the switch reads as a hole punched in the accent rather than a lamp.
 */
@Composable
fun LampToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lamp = LocalLamp.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(LampDimens.toggleWidth, LampDimens.toggleHeight)
            .background(
                if (checked) lamp.accent else lamp.neutral800,
                RoundedCornerShape(LampDimens.toggleHeight / 2),
            )
            // `toggleable`, not `clickable`: it carries the on/off state into semantics, so the
            // switch announces itself to a screen reader instead of reading as a nameless button.
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = null,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(LampDimens.toggleKnob)
                .background(if (checked) lamp.bg else lamp.neutral500, CircleShape),
        )
    }
}

// ---- steppers -----------------------------------------------------------------------------

/**
 * A `label   −  value  +` row. The buttons are at least 44 dp square, which is a floor rather than a
 * preference: 0 → 30 min is six taps, so a short target is felt, not merely measured. The value slot
 * is a fixed width so the buttons never shift under a thumb as the number grows.
 */
@Composable
fun LampStepperRow(
    label: String,
    display: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    buttonWidth: Dp = LampDimens.stepperButton,
    valueWidth: Dp = LampDimens.stepperValueWidth,
    valueStyle: TextStyle = LampType.rowTitleLarge,
) {
    val lamp = LocalLamp.current
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = LampType.rowTitle,
            color = lamp.text,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            StepperButton("−", stepperTag("minus", label), buttonWidth, onMinus)
            Text(
                text = display,
                style = valueStyle.copy(fontFeatureSettings = "tnum"),
                color = lamp.text,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.width(valueWidth),
            )
            StepperButton("+", stepperTag("plus", label), buttonWidth, onPlus)
        }
    }
}

@Composable
private fun StepperButton(symbol: String, tag: String, width: Dp, onClick: () -> Unit) {
    val lamp = LocalLamp.current
    Box(
        modifier = Modifier
            .size(width, LampDimens.stepperButton)
            .border(1.dp, lamp.divider, RoundedCornerShape(LampDimens.cardRadius))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = LampType.sectionTitle, color = lamp.text)
    }
}

// ---- input --------------------------------------------------------------------------------

/** `surface` fill, 1 px divider border, 8 dp radius — the only text input in the app. */
@Composable
fun LampTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LampType.rowTitle,
) {
    val lamp = LocalLamp.current
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 40.dp)
            .background(lamp.surface, RoundedCornerShape(LampDimens.cardRadius))
            .border(1.dp, lamp.divider, RoundedCornerShape(LampDimens.cardRadius))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = textStyle, color = lamp.neutral600)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = textStyle.copy(color = lamp.text),
            cursorBrush = SolidColor(lamp.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---- bottom sheet -------------------------------------------------------------------------

/**
 * The one bottom sheet: 14 dp top corners, `surface`, a 32 × 4 dp handle in `neutral-700`.
 *
 * Sheets, not dialogs, for everything that edits: the schedule editor's seven day chips alone need
 * ~290 dp and a dialog's insets leave under 280 dp on this phone — the reason the current app moved
 * limit editing to a sheet in the first place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LampSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val lamp = LocalLamp.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = RoundedCornerShape(topStart = LampDimens.sheetRadius, topEnd = LampDimens.sheetRadius),
        containerColor = lamp.surface,
        contentColor = lamp.text,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = 0.5f),
        dragHandle = {
            Box(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(LampDimens.sheetHandleWidth, LampDimens.sheetHandleHeight)
                        .background(lamp.neutral700, RoundedCornerShape(2.dp)),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                // The limits sheet with its schedule editor open is taller than the sheet's own
                // maximum on a 892 dp phone, so the content scrolls rather than clipping the
                // From/To steppers off the bottom where they can't be reached.
                .verticalScroll(rememberScrollState())
                .padding(
                    start = LampDimens.screenPadding,
                    end = LampDimens.screenPadding,
                    bottom = LampDimens.sheetBottomPadding,
                ),
            content = content,
        )
    }
}

// ---- table rows ---------------------------------------------------------------------------

/**
 * A fact row: a muted label left, a value right. The block screen, the exception sheet and the Lock
 * tab all state costs this way, so they read as the same kind of statement.
 */
@Composable
fun FactRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    valueStyle: TextStyle = LampType.body,
) {
    val lamp = LocalLamp.current
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = LampType.body, color = lamp.neutral500, modifier = Modifier.weight(1f))
        Text(value, style = valueStyle, color = valueColor ?: lamp.text)
    }
}

/** A table header: a flexible first column, then fixed-width right-aligned ones. */
@Composable
fun TableHeader(first: String, vararg columns: Pair<String, Dp>, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(bottom = 9.dp)) {
        SectionLabel(first, Modifier.weight(1f))
        for ((label, width) in columns) {
            Text(
                text = label.uppercase(),
                style = LampType.sectionLabel,
                color = LocalLamp.current.neutral600,
                textAlign = TextAlign.End,
                modifier = Modifier.width(width),
            )
        }
    }
}

/**
 * The `used / cap` cell: one right-aligned column with the cap in `neutral-600` after the slash.
 *
 * Deliberately **one** `Text` with a span, not two side by side. Two would put the slash at the
 * mercy of the left number's width, and — more importantly — a two-`Text` `Row` has no single
 * baseline for the table row to align the app name against, which is exactly the alignment the
 * design asks for (a row with a sub-line must not push its number down).
 */
@Composable
fun UsedCapCell(
    used: String,
    cap: String,
    width: Dp,
    modifier: Modifier = Modifier,
    style: TextStyle = LampType.number,
) {
    val lamp = LocalLamp.current
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = lamp.text)) { append(used) }
        withStyle(SpanStyle(color = lamp.neutral600)) { append(" / $cap") }
    }
    Text(
        text = text,
        style = style,
        textAlign = TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier.width(width),
    )
}
