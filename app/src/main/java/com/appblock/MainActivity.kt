package com.appblock

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblock.data.PrefsEngineStore
import com.appblock.engine.Access
import com.appblock.engine.AppTargets
import com.appblock.engine.BudgetCoordinator
import com.appblock.engine.ExceptionState
import com.appblock.engine.Target
import com.appblock.engine.TargetStatus
import com.appblock.service.AndroidEngineClock
import com.appblock.ui.theme.AppBlockTheme
import com.appblock.util.accessibilitySettingsIntent
import com.appblock.util.isAccessibilityServiceEnabled
import com.appblock.util.overlayPermissionIntent
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    // Re-checked in onResume so the setup card updates when the user returns from Settings.
    private val accessibilityEnabled = mutableStateOf(false)
    private val overlayGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppBlockTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HomeScreen(
                        accessibilityEnabled = accessibilityEnabled.value,
                        overlayGranted = overlayGranted.value,
                        onOpenAccessibility = { startActivity(accessibilitySettingsIntent()) },
                        onOpenOverlay = { startActivity(overlayPermissionIntent(this)) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accessibilityEnabled.value = isAccessibilityServiceEnabled(this)
        overlayGranted.value = Settings.canDrawOverlays(this)
    }
}

@Composable
private fun HomeScreen(
    accessibilityEnabled: Boolean,
    overlayGranted: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
) {
    val context = LocalContext.current
    // The UI's own coordinator over the same prefs store the service writes to (one process → shared
    // state). It never calls onForeground, so it only reads usage and advances/edits exceptions.
    val coordinator = remember {
        val clock = AndroidEngineClock()
        BudgetCoordinator(clock, PrefsEngineStore(context, clock), ActiveRules.rules)
    }

    var statuses by remember { mutableStateOf<List<TargetStatus>>(emptyList()) }
    var dialogTarget by remember { mutableStateOf<TargetStatus?>(null) }

    // Poll once a second so time-left and exception countdowns tick live.
    LaunchedEffect(Unit) {
        while (true) {
            statuses = coordinator.snapshot()
            delay(1_000)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "App-Block",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Daily budgets · resets 4am",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            SetupCard(
                accessibilityEnabled = accessibilityEnabled,
                overlayGranted = overlayGranted,
                onOpenAccessibility = onOpenAccessibility,
                onOpenOverlay = onOpenOverlay,
            )
        }

        items(statuses, key = { it.target.key }) { status ->
            TargetCard(
                status = status,
                onRequestException = { dialogTarget = status },
                onCancelException = { coordinator.cancelException(status.target) },
            )
        }
    }

    dialogTarget?.let { status ->
        ExceptionDialog(
            status = status,
            onDismiss = { dialogTarget = null },
            onConfirm = { extraMinutes, windowMinutes ->
                coordinator.requestException(status.target, extraMinutes, windowMinutes)
                dialogTarget = null
            },
        )
    }
}

@Composable
private fun TargetCard(
    status: TargetStatus,
    onRequestException: () -> Unit,
    onCancelException: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = labelFor(status.target),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (status.access == Access.BLOCK) "Blocked" else "Open",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (status.access == Access.BLOCK) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

            Spacer(Modifier.height(6.dp))

            val capSeconds = status.effectiveCapMinutes * 60L
            val fraction =
                if (capSeconds <= 0L) 1f
                else (status.usedSeconds.toFloat() / capSeconds.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "${formatDuration(status.remainingSeconds)} left of " +
                    "${status.effectiveCapMinutes} min today",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (!AppTargets.isEnforced(status.target)) {
                Text(
                    text = "Not enforced yet — needs on-device Reels/Explore detection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ExceptionLine(status)

            Spacer(Modifier.height(10.dp))

            when (status.exception) {
                is ExceptionState.None ->
                    if (AppTargets.isEnforced(status.target) && status.exceptionMaxMinutes > status.normalCapMinutes) {
                        OutlinedButton(onClick = onRequestException) { Text("Request more time") }
                    }
                else ->
                    OutlinedButton(onClick = onCancelException) { Text("Cancel exception") }
            }
        }
    }
}

@Composable
private fun ExceptionLine(status: TargetStatus) {
    val text = when (val exc = status.exception) {
        is ExceptionState.None -> null
        is ExceptionState.Pending -> {
            val waitLeft = status.exceptionActivatesInMs ?: 0L
            "Exception in ${formatDuration(waitLeft / 1000)} → then +${exc.extraMinutes} min " +
                "for ${formatMinutesWindow(exc.windowMinutes)}"
        }
        is ExceptionState.Active -> {
            val endsIn = status.exceptionEndsInMs ?: 0L
            "Exception active: +${exc.extraMinutes} min, ends in ${formatDuration(endsIn / 1000)}"
        }
    }
    if (text != null) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ExceptionDialog(
    status: TargetStatus,
    onDismiss: () -> Unit,
    onConfirm: (extraMinutes: Int, windowMinutes: Int) -> Unit,
) {
    val maxExtra = (status.exceptionMaxMinutes - status.normalCapMinutes).coerceAtLeast(EXTRA_STEP)
    var extra by remember { mutableStateOf(EXTRA_STEP.coerceAtMost(maxExtra)) }
    var window by remember { mutableStateOf(DEFAULT_WINDOW_MINUTES) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("More time — ${labelFor(status.target)}") },
        text = {
            Column {
                Text(
                    text = "Raises the cap from ${status.normalCapMinutes} to " +
                        "${(status.normalCapMinutes + extra).coerceAtMost(status.exceptionMaxMinutes)} min " +
                        "(max ${status.exceptionMaxMinutes}).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Stepper(
                    label = "Extra",
                    value = "+$extra min",
                    onMinus = { extra = (extra - EXTRA_STEP).coerceAtLeast(EXTRA_STEP) },
                    onPlus = { extra = (extra + EXTRA_STEP).coerceAtMost(maxExtra) },
                )
                Spacer(Modifier.height(8.dp))
                Stepper(
                    label = "For",
                    value = formatMinutesWindow(window),
                    onMinus = { window = (window - WINDOW_STEP_MINUTES).coerceAtLeast(WINDOW_STEP_MINUTES) },
                    onPlus = { window = (window + WINDOW_STEP_MINUTES).coerceAtMost(MAX_WINDOW_MINUTES) },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "You'll wait 1 hour before it starts. Blocks stay on during the wait.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(extra, window) }) { Text("Start 1-hour wait") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun Stepper(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onMinus) { Text("−") }
            Text(
                text = value,
                modifier = Modifier.width(96.dp).padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedButton(onClick = onPlus) { Text("+") }
        }
    }
}

@Composable
private fun SetupCard(
    accessibilityEnabled: Boolean,
    overlayGranted: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
) {
    if (accessibilityEnabled && overlayGranted) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Setup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Both are special permissions — you grant them once in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            PermissionRow(
                label = "Accessibility service",
                granted = accessibilityEnabled,
                actionLabel = "Enable",
                onAction = onOpenAccessibility,
            )
            PermissionRow(
                label = "Display over other apps",
                granted = overlayGranted,
                actionLabel = "Grant",
                onAction = onOpenOverlay,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (granted) "On" else "Not granted",
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        if (!granted) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

private fun labelFor(target: Target): String = when (target) {
    Target.TIKTOK -> "TikTok"
    Target.INSTAGRAM_REELS_EXPLORE -> "Instagram Reels & Explore"
    Target.X -> "X"
}

/** mm:ss for anything under an hour, else h:mm:ss. */
private fun formatDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0L)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** A window length in minutes as "45 min", "2 h", or "1 h 30 m". */
private fun formatMinutesWindow(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m m"
    }
}

private const val EXTRA_STEP = 5
private const val WINDOW_STEP_MINUTES = 30
private const val DEFAULT_WINDOW_MINUTES = 60
private const val MAX_WINDOW_MINUTES = 24 * 60
