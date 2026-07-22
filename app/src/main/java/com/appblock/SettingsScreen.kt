package com.appblock

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblock.engine.ChangeDirection
import com.appblock.engine.ChangeResult
import com.appblock.engine.DurableChangeGate
import com.appblock.engine.DurableSettings
import com.appblock.engine.RuleStore
import com.appblock.engine.Target
import com.appblock.engine.TargetSettings
import com.appblock.security.GeneratedKey
import com.appblock.security.LockKeys
import com.appblock.security.LockStore
import com.appblock.security.UnlockSession
import com.appblock.security.qrBitmap
import kotlinx.coroutines.delay

/**
 * The gated durable-settings editor (CONSTRAINTS.md §6). You can always *tighten* (lower a cap, turn a
 * block on, shorten the exception window) — those save immediately. *Loosening* anything requires an
 * active unlock session opened by entering your stashed key. The gate is enforced at save via
 * [DurableChangeGate], and also surfaced live so the intent is clear before you tap Save.
 */
@Composable
fun SettingsScreen(
    ruleStore: RuleStore,
    lockStore: LockStore,
    onBack: () -> Unit,
) {
    var refresh by remember { mutableStateOf(0) }
    val current by remember(refresh) { mutableStateOf(ruleStore.load()) }
    var draft by remember(current) { mutableStateOf(current) }
    val configured by remember(refresh) { mutableStateOf(lockStore.isConfigured()) }

    var showSetup by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // Re-tick each second so the unlock countdown updates and the gate hint stays live.
    var nowTick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            nowTick = UnlockSession.remainingMs()
            delay(1_000)
        }
    }
    val unlocked = UnlockSession.isUnlocked()
    val direction = DurableChangeGate.classify(current, draft)
    val dirty = draft != current

    fun updateTarget(target: Target, block: (TargetSettings) -> TargetSettings) {
        val ts = draft.targets[target] ?: return
        draft = draft.copy(targets = draft.targets + (target to block(ts)))
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                )
                TextButton(onClick = onBack) { Text("Done") }
            }
        }

        item { LockStatusCard(configured, unlocked, onCreateKey = { showSetup = true }, onUnlock = { showUnlock = true }, onRelock = { UnlockSession.relock(); message = null }) }

        message?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) } }

        // Per-target editors.
        for (target in Target.entries) {
            val ts = draft.targets[target] ?: continue
            item(key = "target_${target.key}") {
                TargetEditor(
                    label = labelForSettings(target),
                    settings = ts,
                    onEnabledChange = { on -> updateTarget(target) { it.copy(enabled = on) } },
                    onWeekday = { v -> updateTarget(target) { it.copy(weekdayMinutes = v) } },
                    onWeekend = { v -> updateTarget(target) { it.copy(weekendMinutes = v) } },
                    onMax = { v -> updateTarget(target) { it.copy(exceptionMaxMinutes = v) } },
                )
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Temporary exception", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(
                        "How long a granted exception's raised cap lasts. A durable pre-set — you still pick +minutes in the moment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    IntStepper(
                        label = "Window",
                        value = draft.exceptionWindowMinutes,
                        display = formatWindow(draft.exceptionWindowMinutes),
                        step = 30,
                        min = 30,
                        max = 24 * 60,
                        onChange = { draft = draft.copy(exceptionWindowMinutes = it) },
                    )
                }
            }
        }

        item {
            if (dirty && direction == ChangeDirection.LOOSEN && !unlocked) {
                Text(
                    "This loosens your limits — unlock with your stashed key to save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = dirty,
                    onClick = {
                        when (val result = DurableChangeGate.applyChange(current, draft, UnlockSession.isUnlocked())) {
                            is ChangeResult.Applied -> {
                                ruleStore.save(result.settings)
                                refresh++
                                message = "Saved."
                            }
                            is ChangeResult.Blocked -> {
                                message = null
                                showUnlock = true
                            }
                        }
                    },
                ) { Text(if (direction == ChangeDirection.LOOSEN && !unlocked) "Unlock to save" else "Save") }
                OutlinedButton(enabled = dirty, onClick = { draft = current; message = null }) { Text("Revert") }
            }
        }
    }

    if (showSetup) {
        KeySetupDialog(
            onConfirm = { generated ->
                lockStore.setKey(generated)
                showSetup = false
                refresh++
                message = "Lock key set. Stash the QR somewhere you can't reach on impulse."
            },
            onDismiss = { showSetup = false },
        )
    }

    if (showUnlock) {
        UnlockDialog(
            verify = { code -> lockStore.verify(code) },
            onUnlocked = {
                UnlockSession.unlock()
                showUnlock = false
                message = "Unlocked for ${UnlockSession.DURATION_MS / 60_000} minutes."
            },
            onDismiss = { showUnlock = false },
        )
    }
}

@Composable
private fun LockStatusCard(
    configured: Boolean,
    unlocked: Boolean,
    onCreateKey: () -> Unit,
    onUnlock: () -> Unit,
    onRelock: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Durable-change lock", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            when {
                !configured -> {
                    Text(
                        "No key set — anything can be loosened. Create a key, then stash its QR off the phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    Button(onClick = onCreateKey) { Text("Create lock key") }
                }
                unlocked -> {
                    Text(
                        "Unlocked — ${formatMillis(UnlockSession.remainingMs())} left to make loosening changes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    OutlinedButton(onClick = onRelock) { Text("Relock now") }
                }
                else -> {
                    Text(
                        "Locked. Tightening saves freely; loosening needs your stashed key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    OutlinedButton(onClick = onUnlock) { Text("Unlock") }
                }
            }
        }
    }
}

@Composable
private fun TargetEditor(
    label: String,
    settings: TargetSettings,
    onEnabledChange: (Boolean) -> Unit,
    onWeekday: (Int) -> Unit,
    onWeekend: (Int) -> Unit,
    onMax: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Switch(checked = settings.enabled, onCheckedChange = onEnabledChange)
            }
            if (settings.enabled) {
                Spacer(Modifier.height(8.dp))
                IntStepper("Weekday cap", settings.weekdayMinutes, "${settings.weekdayMinutes} min", 5, 0, 24 * 60, onWeekday)
                Spacer(Modifier.height(6.dp))
                IntStepper("Weekend cap", settings.weekendMinutes, "${settings.weekendMinutes} min", 5, 0, 24 * 60, onWeekend)
                Spacer(Modifier.height(6.dp))
                IntStepper("Exception ceiling", settings.exceptionMaxMinutes, "${settings.exceptionMaxMinutes} min", 5, 0, 24 * 60, onMax)
            } else {
                Text(
                    "Off — this app isn't limited.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun IntStepper(
    label: String,
    value: Int,
    display: String,
    step: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onChange((value - step).coerceAtLeast(min)) }) { Text("−") }
            Text(display, modifier = Modifier.width(84.dp).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = { onChange((value + step).coerceAtMost(max)) }) { Text("+") }
        }
    }
}

@Composable
private fun KeySetupDialog(
    onConfirm: (GeneratedKey) -> Unit,
    onDismiss: () -> Unit,
) {
    val generated = remember { LockKeys.generate() }
    val qr = remember(generated) { qrBitmap(generated.code, 640) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your lock key") },
        text = {
            Column {
                Text(
                    "Photograph or print this QR and stash it somewhere inconvenient — a drawer, a friend, off the phone. Then delete the photo from this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "Lock key QR code",
                    modifier = Modifier.size(220.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("Code (the QR's contents):", style = MaterialTheme.typography.labelMedium)
                Text(generated.code, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Only a one-way hash is stored — the app can't show you this again. Lose it and you'll change rules only by rebuilding from your computer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { onConfirm(generated) }) { Text("I've stashed it — lock it in") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun UnlockDialog(
    verify: (String) -> Boolean,
    onUnlocked: () -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unlock to loosen") },
        text = {
            Column {
                Text(
                    "Enter the code from your stashed QR. Opens a ${UnlockSession.DURATION_MS / 60_000}-minute window to make loosening changes.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; error = false },
                    label = { Text("Key code") },
                    singleLine = true,
                    isError = error,
                )
                if (error) {
                    Text("That code doesn't match.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (verify(code)) onUnlocked() else error = true }) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun labelForSettings(target: Target): String = when (target) {
    Target.TIKTOK -> "TikTok"
    Target.INSTAGRAM_REELS_EXPLORE -> "Instagram Reels & Explore"
    Target.X -> "X"
}

private fun formatWindow(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m m"
    }
}

private fun formatMillis(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
