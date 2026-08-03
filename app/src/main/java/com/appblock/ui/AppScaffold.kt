package com.appblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.appblock.R
import com.appblock.ui.theme.LampDimens
import com.appblock.ui.theme.LampType
import com.appblock.ui.theme.LocalLamp

/**
 * The four subjects the app is about. Replaces `MainActivity`'s `showSettings` boolean, which forced
 * one enormous settings scroll to hold app limits, website limits, the lock and the permission
 * warnings all at once — eleven cards of identical weight with no way to say which mattered.
 */
enum class AppTab(val labelRes: Int, val icon: Int, val activeIcon: Int) {
    TODAY(R.string.tab_today, R.drawable.ic_ph_clock, R.drawable.ic_ph_clock_fill),
    APPS(R.string.tab_apps, R.drawable.ic_ph_squares_four, R.drawable.ic_ph_squares_four_fill),
    SITES(R.string.tab_sites, R.drawable.ic_ph_globe_simple, R.drawable.ic_ph_globe_simple_fill),
    LOCK(R.string.tab_lock, R.drawable.ic_ph_lock_simple, R.drawable.ic_ph_lock_simple_fill),
}

/**
 * The frame every screen sits in: scrolling content above, the tab bar pinned below. The weighted
 * column is the pattern the old settings screen already used to pin its save bar — the reason being
 * that content which scrolls away can be walked past, and the tab bar must not be.
 *
 * [toast] is the phase-message slot. It floats above the tab bar rather than inside the scroll,
 * because a message about the current phase ("wait started") has to be visible without scrolling and
 * has to be dismissible when the phase ends.
 */
@Composable
fun LampScaffold(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    toast: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LocalLamp.current.bg)
            .safeDrawingPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { content() }
            LampTabBar(selected = selected, onSelect = onSelect)
        }
        toast?.invoke(this)
    }
}

/** 1 px divider top edge, a 19 dp glyph and a 10 sp label. Active is the filled glyph in accent. */
@Composable
fun LampTabBar(selected: AppTab, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    val lamp = LocalLamp.current
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(lamp.divider))
        Row(Modifier.fillMaxWidth()) {
            for (tab in AppTab.entries) {
                val active = tab == selected
                val label = stringResource(tab.labelRes)
                val interaction = remember { MutableInteractionSource() }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            role = Role.Tab,
                        ) { onSelect(tab) }
                        .padding(top = 9.dp, bottom = 11.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    PhosphorIcon(
                        painter = phosphor(if (active) tab.activeIcon else tab.icon),
                        contentDescription = label,
                        tint = if (active) lamp.accent else lamp.neutral500,
                        size = 19.dp,
                    )
                    Text(
                        text = label,
                        style = LampType.tabLabel,
                        color = if (active) lamp.accent else lamp.neutral500,
                    )
                }
            }
        }
    }
}

/**
 * The phase message. Deliberately not a Material `Snackbar`: it must survive until either the user
 * dismisses it or its phase ends, and a snackbar's timeout would take "your window is open" off
 * screen while the window is still open.
 */
@Composable
fun BoxScope.LampToast(text: String, onDismiss: () -> Unit) {
    val lamp = LocalLamp.current
    Row(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(start = 12.dp, end = 12.dp, bottom = 64.dp)
            .fillMaxWidth()
            .background(lamp.surface, RoundedCornerShape(LampDimens.cardRadius))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhosphorIcon(phosphor(R.drawable.ic_ph_info), null, tint = lamp.accent, size = 16.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = LampType.meta, color = lamp.text, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        LampButton(
            text = stringResource(R.string.action_ok),
            onClick = onDismiss,
            style = LampButtonStyle.Ghost,
            textStyle = LampType.metaSmall,
            minHeight = 28.dp,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}
