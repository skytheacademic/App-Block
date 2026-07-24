package com.appblock

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblock.engine.ChangeDirection
import com.appblock.engine.ChangeResult
import com.appblock.engine.DurableChangeGate
import com.appblock.engine.DurableUnlockManager
import com.appblock.engine.DurableUnlockState
import com.appblock.engine.RuleStore
import com.appblock.engine.Schedule
import com.appblock.engine.ScheduleEditorModel
import com.appblock.engine.Target
import com.appblock.engine.TargetSettings
import com.appblock.engine.UnlockCategory
import com.appblock.engine.WindowRule
import java.time.DayOfWeek
import com.appblock.security.BlocklistStore
import com.appblock.security.DurableUnlockController
import com.appblock.security.GeneratedKey
import com.appblock.security.LockKeys
import com.appblock.security.LockStore
import com.appblock.security.qrBitmap
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay

/**
 * The gated durable-settings editor (CONSTRAINTS.md §6, model revised 2026-07-22). *Tightening* saves
 * immediately, no key, no wait. *Loosening* requires the delayed single-use window: enter the stashed
 * key → **2-hour wait** → **15-minute window** (announced by a notification) → one Accept, which
 * applies the change and relocks. Miss it, or reboot during the wait, and the cycle restarts.
 */
@Composable
fun SettingsScreen(
    ruleStore: RuleStore,
    lockStore: LockStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val unlockController = remember { DurableUnlockController(context) }

    var refresh by remember { mutableStateOf(0) }
    val current by remember(refresh) { mutableStateOf(ruleStore.load()) }
    var draft by remember(current) { mutableStateOf(current) }
    val configured by remember(refresh) { mutableStateOf(lockStore.isConfigured()) }

    var unlockState by remember { mutableStateOf<DurableUnlockState>(DurableUnlockState.Locked) }
    var remainingMs by remember { mutableStateOf(0L) }
    var showSetup by remember { mutableStateOf(false) }
    var startCategory by remember { mutableStateOf<UnlockCategory?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val blocklistStore = remember { BlocklistStore(context) }
    val blocklist by remember(refresh) { mutableStateOf(blocklistStore.domains()) }
    var newDomain by remember { mutableStateOf("") }

    // Advance + persist the unlock state each second; keep the live countdown fresh.
    LaunchedEffect(Unit) {
        while (true) {
            val s = unlockController.state()
            unlockState = s
            remainingMs = when (s) {
                is DurableUnlockState.Pending -> DurableUnlockManager.msUntilOpen(s, SystemClock.elapsedRealtime())
                is DurableUnlockState.Open -> DurableUnlockManager.msUntilClose(s, SystemClock.elapsedRealtime())
                else -> 0L
            }
            delay(1_000)
        }
    }

    // This screen edits *app* rules, so only an APPS-category window authorizes a loosening here.
    val open = DurableUnlockManager.isOpenFor(unlockState, UnlockCategory.APPS)
    // Blocklist removals are gated by the *websites* window (72-hour), a separate cycle from apps.
    val webOpen = DurableUnlockManager.isOpenFor(unlockState, UnlockCategory.WEBSITES)
    val direction = DurableChangeGate.classify(current, draft)
    val dirty = draft != current
    val loosening = direction == ChangeDirection.LOOSEN
    val canSave = dirty && (!loosening || open)

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
                Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                TextButton(onClick = onBack) { Text("Done") }
            }
        }

        item {
            LockStatusCard(
                configured = configured,
                state = unlockState,
                remainingMs = remainingMs,
                onCreateKey = { showSetup = true },
                onStart = { startCategory = UnlockCategory.APPS },
                onCancel = { unlockController.cancel(); message = "Change window cancelled." },
            )
        }

        message?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) } }

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
                    onScheduleChange = { sched -> updateTarget(target) { it.copy(schedule = sched) } },
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
            BlocklistSection(
                domains = blocklist,
                webOpen = webOpen,
                newDomain = newDomain,
                onNewDomainChange = { newDomain = it; message = null },
                onAdd = {
                    val added = blocklistStore.add(newDomain)
                    if (added != null) {
                        newDomain = ""
                        refresh++
                        message = "Blocked $added."
                    } else {
                        message = "That doesn't look like a website address."
                    }
                },
                onRemove = { domain ->
                    if (blocklistStore.removeIfAuthorized(domain, webOpen)) {
                        unlockController.consume()   // single-use: one site per website window
                        refresh++
                        message = "Removed $domain — that was your one change, locked again."
                    } else {
                        message = "Removing a site needs the 72-hour website window."
                    }
                },
                onStartWebsiteWindow = { startCategory = UnlockCategory.WEBSITES },
            )
        }

        item {
            if (dirty && loosening && !open) {
                val hint = when (unlockState) {
                    is DurableUnlockState.Pending ->
                        "This loosens your limits — it'll save once your window opens (in ${formatHms(remainingMs)})."
                    else ->
                        "This loosens your limits — start the change window above (2-hour wait) to save it."
                }
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 4.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = canSave,
                    onClick = {
                        when (val result = DurableChangeGate.applyChange(current, draft, open)) {
                            is ChangeResult.Applied -> {
                                ruleStore.save(result.settings)
                                if (loosening) {
                                    unlockController.consume()   // single-use: this was your one change
                                    message = "Saved. That was your one change — it's locked again."
                                } else {
                                    message = "Saved."
                                }
                                refresh++
                            }
                            is ChangeResult.Blocked -> message = "That loosens your limits — start the change window first."
                        }
                    },
                ) { Text(if (loosening) "Accept one change" else "Save") }
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

    startCategory?.let { category ->
        StartWindowDialog(
            category = category,
            verify = { code -> lockStore.verify(code) },
            onVerified = {
                unlockController.request(category)
                startCategory = null
                message = "Wait started. You'll get a notification when your change window opens."
            },
            onDismiss = { startCategory = null },
        )
    }
}

@Composable
private fun LockStatusCard(
    configured: Boolean,
    state: DurableUnlockState,
    remainingMs: Long,
    onCreateKey: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
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
                state is DurableUnlockState.Pending -> {
                    Text(
                        "Change window opens in ${formatHms(remainingMs)}. Blocks stay on until then; you'll get a notification.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    OutlinedButton(onClick = onCancel) { Text("Cancel wait") }
                }
                state is DurableUnlockState.Open -> {
                    Text(
                        "Open — ${formatHms(remainingMs)} left. Make ONE change below and tap Accept; it locks again after.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    OutlinedButton(onClick = onCancel) { Text("Cancel window") }
                }
                else -> {
                    Text(
                        "Locked. Tightening saves freely; loosening needs a 2-hour wait started with your stashed key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    OutlinedButton(onClick = onStart) { Text("Start change window") }
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
    onScheduleChange: (Schedule?) -> Unit,
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
                Spacer(Modifier.height(10.dp))
                ScheduleEditor(schedule = settings.schedule, onScheduleChange = onScheduleChange)
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

/**
 * Schedule authoring on the engine's full per-day model: a list of window rules, each "these days,
 * this From→To range". Stepping To past midnight (so To ≤ From) authors an overnight span in one
 * gesture — [ScheduleEditorModel] compiles it to two engine windows (evening + next-day morning).
 * Extra rules give different hours on different days, or several windows in one day.
 */
@Composable
private fun ScheduleEditor(
    schedule: Schedule?,
    onScheduleChange: (Schedule?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Limit to certain hours", style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = schedule != null,
            onCheckedChange = { on ->
                onScheduleChange(if (on) ScheduleEditorModel.toSchedule(listOf(DEFAULT_WINDOW_RULE)) else null)
            },
        )
    }

    if (schedule != null) {
        // Local authoring state so a half-edited rule (say, no days picked yet — it compiles to
        // nothing) survives recomposition; re-derived only when the schedule changed underneath us
        // (Revert, the toggle, an external edit).
        var rules by remember { mutableStateOf(ScheduleEditorModel.decompose(schedule)) }
        if (ScheduleEditorModel.toSchedule(rules) != schedule) {
            rules = ScheduleEditorModel.decompose(schedule)
        }

        fun update(newRules: List<WindowRule>) {
            rules = newRules
            onScheduleChange(ScheduleEditorModel.toSchedule(newRules))
        }

        rules.forEachIndexed { i, rule ->
            Spacer(Modifier.height(8.dp))
            WindowRuleEditor(
                rule = rule,
                showRemove = rules.size > 1,
                onChange = { changed -> update(rules.toMutableList().also { it[i] = changed }) },
                onRemove = { update(rules.filterIndexed { j, _ -> j != i }) },
            )
        }
        TextButton(onClick = { update(rules + DEFAULT_WINDOW_RULE) }) { Text("+ Add hours") }
        Text(
            "Allowed only inside these hours on their days. Blocked otherwise — and all day on days no rule covers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (day in DayOfWeek.entries) {
            DayChip(
                label = dayLabel(day),
                selected = day in rule.days,
                onClick = {
                    val days = if (day in rule.days) rule.days - day else rule.days + day
                    onChange(rule.copy(days = days))
                },
            )
        }
    }
    if (rule.days.isEmpty()) {
        Text(
            "Pick at least one day — with none, this window does nothing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    ClockStepper("From", rule.startMin, skip = rule.endMin) { onChange(rule.copy(startMin = it)) }
    Spacer(Modifier.height(6.dp))
    ClockStepper("To", rule.endMin, skip = rule.startMin) { onChange(rule.copy(endMin = it)) }
    if (rule.overnight) {
        Text(
            "Runs past midnight: ${formatHm(rule.startMin)} until ${formatHm(rule.endMin)} the next morning.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (showRemove) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onRemove) { Text("Remove these hours") }
        }
    }
}

/**
 * A 24-hour clock stepper in 30-min steps that wraps at midnight — stepping To past 23:30 rolls to
 * 00:00 and onward, which is how an overnight window is authored in one gesture.
 */
@Composable
private fun ClockStepper(label: String, value: Int, skip: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onChange(ScheduleEditorModel.stepClock(value, -30, skip)) }) { Text("−") }
            Text(
                formatHm(value),
                modifier = Modifier.width(84.dp).padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedButton(onClick = { onChange(ScheduleEditorModel.stepClock(value, +30, skip)) }) { Text("+") }
        }
    }
}

@Composable
private fun DayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(38.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                Image(bitmap = qr.asImageBitmap(), contentDescription = "Lock key QR code", modifier = Modifier.size(220.dp))
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
private fun StartWindowDialog(
    category: UnlockCategory,
    verify: (String) -> Boolean,
    onVerified: () -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val waitLabel = if (category == UnlockCategory.WEBSITES) "72-hour" else "2-hour"
    val titleText = if (category == UnlockCategory.WEBSITES) "Start website-removal window" else "Start change window"

    // Camera path: scan the stashed QR instead of typing its code. The scan screen requests the
    // CAMERA permission itself on first use; a match starts the wait immediately.
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents
        if (scanned != null) {
            code = scanned
            if (verify(scanned)) onVerified() else error = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titleText) },
        text = {
            Column {
                Text(
                    "Scan your stashed QR (or type its code) to start the $waitLabel wait. When it's up you'll get a notification and a 15-minute window for one change.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        scanLauncher.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Point the camera at your stashed key QR")
                                .setBeepEnabled(false),
                        )
                    },
                ) { Text("Scan the stashed QR") }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; error = false },
                    label = { Text("Key code (typed fallback)") },
                    singleLine = true,
                    isError = error,
                )
                if (error) {
                    Text("That code doesn't match.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (verify(code)) onVerified() else error = true }) { Text("Start $waitLabel wait") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BlocklistSection(
    domains: List<String>,
    webOpen: Boolean,
    newDomain: String,
    onNewDomainChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onStartWebsiteWindow: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Blocked websites", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(
                "Browse in Chrome or Brave only — other browsers are blocked. Adding a site is instant; " +
                    "removing one takes the 72-hour window (one site per window).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newDomain,
                    onValueChange = onNewDomainChange,
                    label = { Text("Add a domain (e.g. reddit.com)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(enabled = newDomain.isNotBlank(), onClick = onAdd) { Text("Add") }
            }
            if (domains.isEmpty()) {
                Text(
                    "No blocked sites yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Spacer(Modifier.height(4.dp))
                for (domain in domains) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(domain, style = MaterialTheme.typography.bodyMedium)
                        TextButton(enabled = webOpen, onClick = { onRemove(domain) }) { Text("Remove") }
                    }
                }
                if (!webOpen) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onStartWebsiteWindow) { Text("Start 72-hour removal window") }
                }
            }
        }
    }
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

/** h:mm:ss when there are whole hours left, else mm:ss. */
private fun formatHms(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

// ---- schedule editor helpers ----

/** The starter rule when a schedule is first toggled on: every day, 18:00–20:00. */
private val DEFAULT_WINDOW_RULE = WindowRule(DayOfWeek.entries.toSet(), 18 * 60, 20 * 60)

private fun dayLabel(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "M"
    DayOfWeek.TUESDAY -> "T"
    DayOfWeek.WEDNESDAY -> "W"
    DayOfWeek.THURSDAY -> "T"
    DayOfWeek.FRIDAY -> "F"
    DayOfWeek.SATURDAY -> "S"
    DayOfWeek.SUNDAY -> "S"
}

private fun formatHm(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
