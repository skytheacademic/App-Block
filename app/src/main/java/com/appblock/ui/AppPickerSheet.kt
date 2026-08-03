package com.appblock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.appblock.R
import com.appblock.data.InstalledApp
import com.appblock.data.InstalledApps
import com.appblock.ui.theme.LampType
import com.appblock.ui.theme.LocalLamp

/**
 * Pick an installed app to block. Lists launchable apps only — the ~88 things you can actually open,
 * not the ~567 packages on the phone.
 *
 * Picking only edits the draft. Adding a target the settings didn't have is a *tightening*
 * ([com.appblock.engine.DurableChangeGate] reads an absent target as fully open), so it saves freely
 * on the Lock tab; that asymmetry is what lets this exist without becoming a bypass.
 */
@Composable
fun AppPickerSheet(
    excludedPackages: Set<String>,
    onPick: (InstalledApp) -> Unit,
    onDismiss: () -> Unit,
) {
    val lamp = LocalLamp.current
    val context = LocalContext.current
    val apps = remember { InstalledApps.launchable(context).filter { it.packageName !in excludedPackages } }
    var query by remember { mutableStateOf("") }
    val shown = remember(query, apps) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query, ignoreCase = true) }
    }

    LampSheet(onDismiss = onDismiss) {
        Text(
            text = stringResource(R.string.picker_title),
            style = LampType.sectionTitle,
            color = lamp.text,
        )
        Text(
            text = stringResource(R.string.picker_body),
            style = LampType.meta,
            color = lamp.neutral500,
            modifier = Modifier.padding(top = 4.dp),
        )
        LampTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.picker_search),
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        )
        if (shown.isEmpty()) {
            Text(
                text = if (apps.isEmpty()) {
                    stringResource(R.string.picker_all_blocked)
                } else {
                    stringResource(R.string.picker_no_match)
                },
                style = LampType.micro,
                color = lamp.neutral600,
                modifier = Modifier.padding(top = 14.dp),
            )
        } else {
            LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 6.dp)) {
                items(shown, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(app) }
                            .padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = LampType.rowTitleLarge, color = lamp.text)
                            Spacer(Modifier.height(2.dp))
                            // The package name is the faintest thing on the row: it disambiguates
                            // two apps with the same label and is otherwise noise.
                            Text(app.packageName, style = LampType.micro, color = lamp.neutral700)
                        }
                        Spacer(Modifier.width(10.dp))
                        PhosphorIcon(
                            painter = phosphor(R.drawable.ic_ph_caret_right),
                            contentDescription = null,
                            tint = lamp.neutral600,
                            size = 15.dp,
                        )
                    }
                    RowRule()
                }
            }
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
