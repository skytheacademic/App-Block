package com.appblock

import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appblock.data.InstalledApp
import com.appblock.data.InstalledApps
import com.appblock.data.PrefsEngineStore
import com.appblock.engine.AppTargets
import com.appblock.engine.Availability
import com.appblock.engine.ChangeDirection
import com.appblock.engine.ChangeResult
import com.appblock.engine.ConfigExport
import com.appblock.engine.DayBoundary
import com.appblock.engine.DayLabels
import com.appblock.engine.DurableChangeGate
import com.appblock.engine.DurableUnlockManager
import com.appblock.engine.DurableUnlockState
import com.appblock.engine.InstagramSurface
import com.appblock.engine.RuleStore
import com.appblock.engine.Schedule
import com.appblock.engine.ScheduleEditorModel
import com.appblock.engine.Target
import com.appblock.engine.TargetSettings
import com.appblock.engine.TargetSummaries
import com.appblock.engine.TodayUsage
import com.appblock.engine.UnlockCategory
import com.appblock.engine.WindowRule
import java.time.DayOfWeek
import java.time.LocalDate
import com.appblock.security.BlocklistStore
import com.appblock.security.DurableUnlockController
import com.appblock.security.GeneratedKey
import com.appblock.security.LockKeys
import com.appblock.security.LockStore
import com.appblock.security.qrBitmap
import com.appblock.service.AndroidEngineClock
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

    // Live usage for the "used today" bars. The service (writer) and this screen (reader) share a
    // process, so the same SharedPreferences-backed store is the live value, not a stale copy.
    val engineClock = remember { AndroidEngineClock() }
    val engineStore = remember { PrefsEngineStore(context, engineClock) }
    val today = remember(refresh) { DayBoundary.logicalDay(engineClock.nowLocal()) }

    /** Which target's edit sheet is open, if any. */
    var editing by remember { mutableStateOf<Target?>(null) }

    /** True while the add-an-app picker is open (Batch 4). */
    var picking by remember { mutableStateOf(false) }

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
    // One window buys one change (CONSTRAINTS §6). Counted here as well as in the gate so the rule is
    // visible *before* Save rather than only as a rejection — a greyed button with its reason on
    // screen beats one that looks live and then refuses.
    val looseningCount = DurableChangeGate.looseningReasons(current, draft).size
    val canSave = dirty && (!loosening || (open && looseningCount == 1))

    fun updateTarget(target: Target, block: (TargetSettings) -> TargetSettings) {
        val ts = draft.targets[target] ?: return
        draft = draft.copy(targets = draft.targets + (target to block(ts)))
    }

    // Column + weighted LazyColumn so Save/Revert can sit in a pinned bottom bar. They used to be the
    // last item of the scroll: you edit a cap at the top, then scroll past every card to commit it —
    // easy to walk away with the rules you *think* you set still sitting unsaved in the draft.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
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

            // The target set is open now (Batch 4), so iterate what the draft actually holds rather
            // than a fixed enum: built-ins first as seeded, then apps added on-device.
            for (target in draft.targets.keys.toList()) {
                val ts = draft.targets[target] ?: continue
                item(key = "target_${target.key}") {
                    TargetCard(
                        label = labelForSettings(target),
                        scopeNote = scopeNote(target),
                        settings = ts,
                        // Usage reads the *saved* rules, not the draft: it answers "how am I doing
                        // against the cap in force today", which an unsaved edit hasn't changed.
                        usage = current.targets[target]?.let {
                            TargetSummaries.todayUsage(it, engineStore.loadUsage(target), today)
                        },
                        onEnabledChange = { on -> updateTarget(target) { it.copy(enabled = on) } },
                        onEdit = { editing = target },
                        // Built-ins can only be switched off (itself a gated loosening); an app you
                        // added can be dropped entirely. Removal just edits the draft — the gate
                        // classifies a vanished target as LOOSEN on its own, so it costs the 2-hour
                        // window exactly like turning one off does.
                        onRemove = if (target.userPackage != null) {
                            { draft = draft.copy(targets = draft.targets - target) }
                        } else {
                            null
                        },
                    )
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Block another app", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text(
                            "Adding an app is instant. Removing one later needs the 2-hour change window, " +
                                "same as raising a limit.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        Button(onClick = { picking = true }) { Text("Choose an app") }
                    }
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
                // Labels have to be resolved in composition (a user-added app borrows its launcher
                // name), so they're gathered here and handed to the pure renderer as a lookup.
                val labels = current.targets.keys.associateWith { labelForSettings(it) }
                // Platform clipboard rather than Compose's LocalClipboardManager, which is deprecated
                // in favour of a suspend API this one-shot copy has no use for.
                val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
                ExportSection(
                    onCopy = {
                        // Exports what is *saved*, never the draft — a record of rules that were
                        // never committed would be a record of nothing that was ever enforced.
                        val text = ConfigExport.render(
                            settings = current,
                            blockedDomains = blocklist,
                            label = { labels[it] ?: it.key },
                            scopeNote = { scopeNote(it) },
                            today = LocalDate.now(),
                        )
                        clipboard.setPrimaryClip(ClipData.newPlainText("App-Block rules", text))
                        message = "Rules copied. Paste them somewhere that survives this phone."
                    },
                )
            }

        }

        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (dirty) {
                    if (loosening && !open) {
                        val hint = when (unlockState) {
                            is DurableUnlockState.Pending ->
                                "This loosens your limits — it'll save once your window opens (in ${formatHms(remainingMs)})."
                            else ->
                                "This loosens your limits — start the change window above (2-hour wait) to save it."
                        }
                        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 4.dp))
                    }
                    if (loosening && open && looseningCount > 1) {
                        // The window is open and still can't take this edit, which is the confusing
                        // case — so name the count and say what to do about it, rather than leaving a
                        // greyed Save under an open window.
                        Text(
                            "Your window covers one change, and this edit loosens $looseningCount. " +
                                "Undo all but the one you want — the others each need their own window.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    // Every pending change, not only the loosening ones. The gate judges the whole
                    // settings object at once, so one loosening anywhere gates everything — and a
                    // warning with nothing listed under it (reported 2026-07-25) leaves you with a
                    // blocked Save and nowhere to look. Showing the full diff means the culprit is
                    // always on screen, and the tightenings give it context.
                    val pending = DurableChangeGate.changes(current, draft)
                    for (change in pending.take(MAX_LISTED_CHANGES)) {
                        val who = change.target?.let { "${labelForSettings(it)}: " } ?: ""
                        val looser = change.direction == ChangeDirection.LOOSEN
                        Text(
                            "• $who${change.detail}${if (looser) "  (loosens)" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (looser) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                    if (pending.size > MAX_LISTED_CHANGES) {
                        Text(
                            "• …and ${pending.size - MAX_LISTED_CHANGES} more",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                    if (pending.isEmpty()) {
                        // Structurally shouldn't happen: `dirty` means draft != current. If it ever
                        // shows, the difference is in a field `changes()` doesn't inspect.
                        Text(
                            "Unsaved changes that this screen can't describe — tap Revert and redo them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                } else {
                    // Save/Revert are disabled with nothing pending; say so rather than leaving two
                    // greyed buttons with no stated reason.
                    Text(
                        "No unsaved changes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = canSave,
                        onClick = {
                            when (val result = DurableChangeGate.applyChange(current, draft, open)) {
                                is ChangeResult.Applied -> {
                                    ruleStore.save(result.settings)
                                    // Whatever they just saved is now the config, so a quarantined
                                    // unreadable one has served its purpose and the warning can go.
                                    ruleStore.acknowledgeCorrupt()
                                    if (loosening) {
                                        unlockController.consume()   // single-use: this was your one change
                                        message = "Saved. That was your one change — it's locked again."
                                    } else {
                                        message = "Saved."
                                    }
                                    refresh++
                                }
                                is ChangeResult.Blocked ->
                                    message = "That loosens your limits — start the change window first."
                                // Reachable only if the draft changed between the canSave check and
                                // the tap. The window is deliberately NOT consumed — a refused save
                                // must never cost the cycle.
                                is ChangeResult.TooManyLoosenings ->
                                    message = "A window covers one change; this edit loosens " +
                                        "${result.loosenings.size}. Undo all but one."
                            }
                        },
                    ) { Text(if (loosening) "Accept one change" else "Save") }
                    OutlinedButton(enabled = dirty, onClick = { draft = current; message = null }) { Text("Revert") }
                }
            }
        }
    }

    if (picking) {
        AppPickerDialog(
            // Hide anything already covered: the curated packages, the always-blocked ones, and every
            // app already added. Offering TikTok would create a second, weaker whole-app target beside
            // the real one — and offering Shizuku would be worse than that: adding it reads as a
            // tightening while actually handing a permanently blocked package an editable rule that a
            // 2-hour window could then switch off.
            excludedPackages = AppTargets.packages.keys +
                AppTargets.alwaysBlocked.keys +
                draft.targets.keys.mapNotNull { it.userPackage } +
                InstagramSurface.PACKAGE,
            onPick = { app ->
                val target = Target.forPackage(app.packageName)
                draft = draft.copy(targets = draft.targets + (target to NEW_APP_DEFAULTS))
                picking = false
                message = "Added ${app.label}. Set its limits below, then Save."
            },
            onDismiss = { picking = false },
        )
    }

    editing?.let { target ->
        draft.targets[target]?.let { ts ->
            TargetEditSheet(
                label = labelForSettings(target),
                settings = ts,
                dirty = dirty,
                onWeekday = { v -> updateTarget(target) { it.copy(weekdayMinutes = v) } },
                onWeekend = { v -> updateTarget(target) { it.copy(weekendMinutes = v) } },
                onMax = { v -> updateTarget(target) { it.copy(exceptionMaxMinutes = v) } },
                onScheduleChange = { sched -> updateTarget(target) { it.copy(schedule = sched) } },
                onDismiss = { editing = null },
            )
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

/**
 * The at-a-glance card: what this app's limits actually are, plus today's progress against them.
 * Editing happens in [TargetEditSheet] — the steppers used to sit inline, which meant the numbers
 * you wanted to *read* were buried in the controls you rarely *touch*.
 */
@Composable
private fun TargetCard(
    label: String,
    scopeNote: String?,
    settings: TargetSettings,
    usage: TodayUsage?,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRemove: (() -> Unit)? = null,
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

            Text(
                if (settings.enabled) "Status: on" else "Status: off — this app isn't limited.",
                style = MaterialTheme.typography.bodySmall,
                color = if (settings.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (scopeNote != null) {
                Text(
                    scopeNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            if (settings.enabled) {
                val summary = TargetSummaries.of(settings)
                Spacer(Modifier.height(10.dp))

                for ((i, line) in summary.limits.withIndex()) {
                    SummaryRow(
                        label = if (i == 0) "Time limit" else "",
                        value = formatWindow(line.minutes),
                        days = DayLabels.of(line.days),
                    )
                }
                for ((i, slot) in summary.availability.withIndex()) {
                    SummaryRow(
                        label = if (i == 0) "Available" else "",
                        value = when (slot) {
                            is Availability.AnyTime -> "any time"
                            is Availability.Window -> "${formatHm(slot.startMin)} – ${formatHm(slot.endMin)}"
                            is Availability.BlockedAllDay -> "blocked all day"
                        },
                        days = DayLabels.of(slot.days),
                        alert = slot is Availability.BlockedAllDay,
                    )
                }
                SummaryRow(
                    label = "Can raise to",
                    value = formatWindow(summary.exceptionCeilingMinutes),
                    days = "with an exception",
                )

                if (usage != null) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { usage.fraction },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                    Text(
                        "${usage.minutesUsed} of ${formatWindow(usage.capMinutes)} used today",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove app") }
                    TextButton(onClick = onEdit) { Text("Edit limits") }
                }
            }
        }
    }
}

/**
 * Pick an installed app to block (Batch 4). Lists launchable apps only — the ~88 things you can
 * actually open, not the ~567 packages on the phone.
 *
 * Picking only edits the draft. Adding a target the settings didn't have is a *tightening*
 * ([DurableChangeGate] reads an absent target as fully open), so it saves freely; that asymmetry is
 * what lets this exist without becoming a bypass.
 */
@Composable
private fun AppPickerDialog(
    excludedPackages: Set<String>,
    onPick: (InstalledApp) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val apps = remember { InstalledApps.launchable(context).filter { it.packageName !in excludedPackages } }
    var query by remember { mutableStateOf("") }
    val shown = remember(query, apps) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block an app") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (shown.isEmpty()) {
                    Text(
                        if (apps.isEmpty()) "Every app you can open is already blocked." else "No app matches that.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.height(320.dp)) {
                        items(shown, key = { it.packageName }) { app ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(app) }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One "label — value — days" line of the summary. Blank [label] continues the row above. */
@Composable
private fun SummaryRow(label: String, value: String, days: String, alert: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            days,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The editing surface, as a bottom sheet rather than an AlertDialog: the schedule editor's seven day
 * chips alone need ~290dp, and a dialog's insets leave under 280dp on this phone.
 *
 * It deliberately has **no Save of its own**. [DurableChangeGate] classifies the whole
 * [com.appblock.engine.DurableSettings] at once and a loosening consumes the single-use window, so a
 * second commit point would mean either a second gate implementation or a window consumed twice.
 * The sheet edits the shared draft; the pinned bottom bar stays the only place a change lands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetEditSheet(
    label: String,
    settings: TargetSettings,
    dirty: Boolean,
    onWeekday: (Int) -> Unit,
    onWeekend: (Int) -> Unit,
    onMax: (Int) -> Unit,
    onScheduleChange: (Schedule?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Text("$label limits", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            IntStepper("Weekday cap", settings.weekdayMinutes, formatWindow(settings.weekdayMinutes), 5, 0, 24 * 60, onWeekday)
            Spacer(Modifier.height(6.dp))
            IntStepper("Weekend cap", settings.weekendMinutes, formatWindow(settings.weekendMinutes), 5, 0, 24 * 60, onWeekend)
            Spacer(Modifier.height(6.dp))
            IntStepper("Exception ceiling", settings.exceptionMaxMinutes, formatWindow(settings.exceptionMaxMinutes), 5, 0, 24 * 60, onMax)
            Spacer(Modifier.height(10.dp))
            ScheduleEditor(schedule = settings.schedule, onScheduleChange = onScheduleChange)
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (dirty) "Not saved yet — save on the main screen." else "No unsaved changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (dirty) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onDismiss) { Text("Done") }
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
                modifier = Modifier.weight(1f),
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperButton("−", stepperTag("minus", label)) { onChange(ScheduleEditorModel.stepClock(value, -30, skip)) }
            StepperValue(formatHm(value))
            StepperButton("+", stepperTag("plus", label)) { onChange(ScheduleEditorModel.stepClock(value, +30, skip)) }
        }
    }
}

@Composable
private fun DayChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        // Height only — width comes from the caller's weight(1f), so seven chips always divide the
        // row exactly instead of overflowing once the labels went from one letter to two.
        modifier = modifier.height(44.dp),
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

/**
 * Test handle for one stepper button. Tests used to reach these by global index, which broke twice as
 * the layout moved (Save leaving the scroll container, then the steppers moving into a sheet). A tag
 * keyed on the row's own label survives any rearrangement.
 */
internal fun stepperTag(side: String, label: String): String = "stepper:$side:$label"

/**
 * The +/− control shared by both steppers. Sized explicitly rather than by [OutlinedButton]'s
 * defaults so the tap area clears the 48dp minimum — these get tapped repeatedly (0 → 30 min is six
 * taps), so a short target is felt, not just measured.
 */
@Composable
private fun StepperButton(symbol: String, tag: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.defaultMinSize(minWidth = 56.dp, minHeight = 48.dp).testTag(tag),
        contentPadding = PaddingValues(0.dp),
    ) { Text(symbol, style = MaterialTheme.typography.bodyLarge) }
}

/**
 * The value slot between the two buttons. Fixed width so the buttons never shift under a thumb as
 * the number grows, and wide enough for the longest value the caps can reach ("24 h").
 */
@Composable
private fun StepperValue(display: String) {
    Text(
        display,
        modifier = Modifier.width(96.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge,
    )
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // weight(1f) absorbs the slack so the stepper group is pinned right on *every* row — with
        // SpaceBetween and an intrinsic-width label, the longest label ("Exception ceiling") ran
        // into the − button and shoved its whole group out of line with the rows above.
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperButton("−", stepperTag("minus", label)) { onChange((value - step).coerceAtLeast(min)) }
            StepperValue(display)
            StepperButton("+", stepperTag("plus", label)) { onChange((value + step).coerceAtMost(max)) }
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
                    // Short label + placeholder: the old one-string label wrapped to two lines inside
                    // a weight(1f) field, doubling its height and stranding the Add button.
                    label = { Text("Domain") },
                    placeholder = { Text("reddit.com") },
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

/**
 * Export the saved rules as text (C-7). Read-only by design — there is no matching import, and the
 * section says so, because "copy" without that sentence reads like a backup you could restore.
 */
@Composable
private fun ExportSection(onCopy: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Keep a copy of your rules", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(
                "Copies your caps, schedules, blocked apps and blocked sites as plain text. If this " +
                    "phone is ever wiped, that text is the only record — App-Block can't read it back " +
                    "in, so you'd re-enter it by hand. Your lock key is never included.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            OutlinedButton(onClick = onCopy) { Text("Copy my rules") }
        }
    }
}

/** Built-ins are curated; a user-added app (Batch 4) shows the launcher label for its package. */
@Composable
private fun labelForSettings(target: Target): String = when (target) {
    Target.TIKTOK -> "TikTok"
    Target.INSTAGRAM_REELS_EXPLORE -> "Instagram Reels & Explore"
    Target.X -> "X (Twitter)"
    else -> {
        val context = LocalContext.current
        remember(target) { target.userPackage?.let { InstalledApps.labelFor(context, it) } ?: target.key }
    }
}

/**
 * What the card would otherwise imply wrongly. Instagram is enforced by *surface*, not by package —
 * without this the card reads as though the whole app is capped (CONSTRAINTS.md §1).
 */
private fun scopeNote(target: Target): String? = when (target) {
    Target.INSTAGRAM_REELS_EXPLORE -> "Counts Reels and Explore only. Feed, Stories and DMs stay free."
    else -> null
}

private fun formatWindow(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m min"
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

/**
 * Limits a newly added app starts with. Deliberately a real cap rather than 0: adding at any cap is a
 * tightening (the app had no limit at all a moment ago), and the user can drop it to 0 for free —
 * whereas a default of 0 that felt too harsh would cost a 2-hour window to relax.
 */
/** Cap on the pending-changes list so the bottom bar can't grow without bound. */
private const val MAX_LISTED_CHANGES = 6

private val NEW_APP_DEFAULTS = TargetSettings(
    enabled = true,
    weekdayMinutes = 30,
    weekendMinutes = 30,
    exceptionMaxMinutes = 60,
)

/** The starter rule when a schedule is first toggled on: every day, 18:00–20:00. */
private val DEFAULT_WINDOW_RULE = WindowRule(DayOfWeek.entries.toSet(), 18 * 60, 20 * 60)

/** Chips and summary rows must never disagree about a day's name, so both read [DayLabels]. */
private fun dayLabel(day: DayOfWeek): String = DayLabels.short(day)

private fun formatHm(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
