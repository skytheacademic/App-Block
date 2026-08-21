package com.appblock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.appblock.R
import com.appblock.engine.BrowserTargets
import com.appblock.security.BlockedSite
import com.appblock.ui.theme.LampDimens
import com.appblock.ui.theme.LampType
import com.appblock.ui.theme.LocalLamp

/**
 * Sites: the private blocklist, and the browsers it can be enforced in.
 *
 * The column grammar is Today's on purpose — a flexible subject column and fixed right-hand ones —
 * because the two screens answer the same shape of question about different things.
 */
@Composable
fun SitesScreen(
    sites: List<BlockedSite>,
    newDomain: String,
    inputError: Boolean,
    windowOpen: Boolean,
    /** Time left in an open websites window, or until one opens. Null when locked. */
    windowCountdown: String?,
    canStartWindow: Boolean,
    onNewDomainChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onStartWindow: () -> Unit,
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
        ) {
            Text(
                text = stringResource(R.string.tab_sites),
                style = LampType.screenTitle,
                color = lamp.text,
                modifier = Modifier.alignByBaseline().weight(1f),
            )
            Text(
                text = stringResource(R.string.sites_count, sites.size),
                style = LampType.micro.copy(fontFeatureSettings = "tnum"),
                color = lamp.neutral600,
                modifier = Modifier.alignByBaseline(),
            )
        }
        Text(
            text = stringResource(R.string.sites_sub),
            style = LampType.body,
            color = lamp.neutral500,
            modifier = Modifier.padding(
                start = LampDimens.screenPadding,
                end = LampDimens.screenPadding,
                top = 8.dp,
            ),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = LampDimens.screenPadding, end = LampDimens.screenPadding, top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LampTextField(
                value = newDomain,
                onValueChange = onNewDomainChange,
                placeholder = stringResource(R.string.sites_placeholder),
                modifier = Modifier.weight(1f),
            )
            LampButton(
                text = stringResource(R.string.action_add),
                onClick = onAdd,
                enabled = newDomain.isNotBlank(),
                minHeight = 40.dp,
            )
        }
        Text(
            // Adding is a tightening, so it is free — the asymmetry stated where it applies rather
            // than only in the abstract on Lock.
            text = if (inputError) {
                stringResource(R.string.sites_invalid)
            } else {
                stringResource(R.string.sites_adding_instant)
            },
            style = LampType.micro,
            color = if (inputError) lamp.accent300 else lamp.neutral600,
            modifier = Modifier.padding(
                start = LampDimens.screenPadding,
                end = LampDimens.screenPadding,
                top = 7.dp,
            ),
        )

        Column(
            Modifier.padding(
                start = LampDimens.screenPadding,
                end = LampDimens.screenPadding,
                top = 20.dp,
            ),
        ) {
            TableHeader(
                stringResource(R.string.sites_col_domain),
                stringResource(R.string.sites_col_added) to ADDED_COLUMN,
                stringResource(R.string.sites_col_removal) to REMOVAL_COLUMN,
            )
            TableRule()
            if (sites.isEmpty()) {
                Text(
                    text = stringResource(R.string.sites_empty),
                    style = LampType.micro,
                    color = lamp.neutral600,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            for (site in sites) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = site.domain,
                        style = LampType.rowTitle,
                        color = lamp.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = site.addedAtMillis?.let(::formatAddedDate)
                            ?: stringResource(R.string.sites_added_unknown),
                        style = LampType.meta.copy(fontFeatureSettings = "tnum"),
                        color = lamp.neutral600,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.width(ADDED_COLUMN),
                    )
                    Box(
                        modifier = Modifier.width(REMOVAL_COLUMN),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        if (windowOpen) {
                            LampButton(
                                text = stringResource(R.string.action_remove),
                                onClick = { onRemove(site.domain) },
                                style = LampButtonStyle.Secondary,
                                textStyle = LampType.metaSmall,
                                minHeight = 28.dp,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 9.dp,
                                    vertical = 3.dp,
                                ),
                            )
                        } else {
                            // Not a disabled button: the cell states the *cost*, which is a fact
                            // about the rules rather than a control that happens to be off.
                            Text(
                                text = stringResource(R.string.sites_removal_cost),
                                style = LampType.meta.copy(fontFeatureSettings = "tnum"),
                                color = lamp.neutral500,
                            )
                        }
                    }
                }
                RowRule()
            }
        }

        LampCard(
            modifier = Modifier.padding(
                start = LampDimens.screenPadding,
                end = LampDimens.screenPadding,
                top = 18.dp,
            ),
            padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 13.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhosphorIcon(
                    painter = phosphor(
                        if (windowOpen) R.drawable.ic_ph_lock_open_fill else R.drawable.ic_ph_lock_simple,
                    ),
                    contentDescription = null,
                    tint = if (windowOpen) lamp.accent else lamp.neutral300,
                    size = 16.dp,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (windowOpen) {
                            stringResource(R.string.sites_lock_open_title)
                        } else {
                            stringResource(R.string.sites_lock_title)
                        },
                        style = LampType.body,
                        color = lamp.text,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (windowOpen && windowCountdown != null) {
                            stringResource(R.string.sites_lock_open_sub, windowCountdown)
                        } else if (windowCountdown != null) {
                            stringResource(R.string.sites_lock_pending_sub, windowCountdown)
                        } else {
                            stringResource(R.string.sites_lock_sub)
                        },
                        style = LampType.metaSmall.copy(fontFeatureSettings = "tnum"),
                        color = lamp.neutral500,
                    )
                }
            }
            if (canStartWindow) {
                LampButton(
                    text = stringResource(R.string.sites_start_window),
                    onClick = onStartWindow,
                    style = LampButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth().padding(top = 11.dp),
                    minHeight = 40.dp,
                )
            }
        }

        FadingRule(Modifier.padding(top = 20.dp))
        BrowsersSection(
            modifier = Modifier.padding(
                start = LampDimens.screenPadding,
                end = LampDimens.screenPadding,
                top = 16.dp,
                bottom = 20.dp,
            ),
        )
    }
}

/**
 * Which browsers this can be enforced in — currently invisible in the app, and the single most
 * load-bearing unstated fact on the screen: a blocklist that only works in two browsers, with every
 * other browser blocked as an app, is a very different promise from "sites are blocked".
 *
 * Generated from [BrowserTargets.allowlist] rather than written out, so the section cannot drift
 * from the set the accessibility layer actually URL-watches.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrowsersSection(modifier: Modifier = Modifier) {
    val lamp = LocalLamp.current
    Column(modifier) {
        SectionLabel(stringResource(R.string.sites_browsers), Modifier.padding(bottom = 10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (pkg in BrowserTargets.allowlist.sorted()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PhosphorIcon(phosphor(R.drawable.ic_ph_check), null, tint = lamp.accent, size = 13.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(browserLabel(pkg), style = LampType.body, color = lamp.text)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhosphorIcon(phosphor(R.drawable.ic_ph_prohibit), null, tint = lamp.neutral600, size = 13.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.sites_browsers_other),
                    style = LampType.body,
                    color = lamp.neutral600,
                )
            }
        }
        Text(
            text = stringResource(R.string.sites_browsers_note),
            style = LampType.micro,
            color = lamp.neutral600,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/**
 * A human name for an allowlisted browser package. Falls back to the package itself, so adding a
 * browser to the allowlist can never leave a blank row here — it just reads technically until it
 * gets a name.
 */
private fun browserLabel(packageName: String): String = when (packageName) {
    "com.android.chrome" -> "Chrome"
    "com.brave.browser" -> "Brave"
    else -> packageName
}

/** `12 Jul` — the same shape as the Today header's date, minus the weekday. */
private fun formatAddedDate(millis: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.getDefault())
        .format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))

private val ADDED_COLUMN = 78.dp
private val REMOVAL_COLUMN = 74.dp
