package com.appblock.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.appblock.ActiveRules
import com.appblock.R
import com.appblock.data.OmniboxWitnessStore
import com.appblock.data.PrefsEngineStore
import com.appblock.data.SignalWitnessStore
import com.appblock.engine.AppTargets
import com.appblock.engine.BudgetCoordinator
import com.appblock.engine.ChangeResult
import com.appblock.engine.ConfigExport
import com.appblock.engine.DayBoundary
import com.appblock.engine.DayUsage
import com.appblock.engine.DurableUnlockManager
import com.appblock.engine.DurableUnlockState
import com.appblock.engine.ExceptionState
import com.appblock.engine.InstagramSurface
import com.appblock.engine.SignalCanary
import com.appblock.engine.Target
import com.appblock.engine.TargetSettings
import com.appblock.engine.TargetStatus
import com.appblock.engine.UnlockCategory
import com.appblock.engine.UsageTracker
import com.appblock.security.BlocklistStore
import com.appblock.security.DurableUnlockController
import com.appblock.security.LockStore
import com.appblock.service.AndroidClockIntegrity
import com.appblock.service.AndroidEngineClock
import com.appblock.service.AppBlockerAccessibilityService
import java.time.LocalDate
import kotlinx.coroutines.delay

/**
 * The app, above the tabs: one clock, one coordinator, one draft, one unlock cycle.
 *
 * Everything lives here rather than inside a tab because the redesign split one screen into four and
 * the state did not split with it — Apps edits the draft that Lock commits, Sites consumes the same
 * unlock cycle Lock displays, and Today shows the exceptions the picker starts. Hoisting it is what
 * keeps the four screens views of one thing rather than four little apps that disagree.
 */
@Composable
fun AppRoot(
    accessibilityEnabled: Boolean,
    overlayGranted: Boolean,
    adminActive: Boolean,
    batteryExempt: Boolean,
    notificationsEnabled: Boolean,
    shortcutClaimed: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenDateSettings: () -> Unit,
    onActivateAdmin: () -> Unit,
    onRequestExemption: () -> Unit,
    onAllowNotifications: () -> Unit,
) {
    val context = LocalContext.current
    val clock = remember { AndroidEngineClock() }
    val integrity = remember { AndroidClockIntegrity(context) }
    val engineStore = remember { PrefsEngineStore(context, clock) }
    val ruleStore = remember { ActiveRules.ruleStore(context) }
    val rules = remember { RulesDraft(ruleStore) }
    val lockStore = remember { LockStore(context) }
    val unlockController = remember { DurableUnlockController(context) }
    val blocklistStore = remember { BlocklistStore(context) }
    // The UI's own coordinator over the same prefs store the service writes to — one process, so
    // that store is the live value. It never calls onForeground, so it only reads usage and
    // advances/edits exceptions; the service stays the only thing that accrues time.
    val coordinator = remember {
        BudgetCoordinator(
            clock,
            engineStore,
            integrity,
            ActiveRules.ruleSource(context),
            exceptionWaitMs = ActiveRules.exceptionWaitMs,
        )
    }

    var tab by rememberSaveable {
        mutableStateOf(
            if (unlockController.state() is DurableUnlockState.Locked) AppTab.TODAY else AppTab.LOCK,
        )
    }
    var message by remember { mutableStateOf<String?>(null) }
    var receipt by remember { mutableStateOf<LockReceipt?>(null) }

    var statuses by remember { mutableStateOf<List<TargetStatus>>(emptyList()) }
    var tamperReason by remember { mutableStateOf<String?>(null) }
    var autoTime by remember { mutableStateOf(true) }
    var serviceRunning by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(clock.nowLocal()) }
    var unlockState by remember { mutableStateOf<DurableUnlockState>(DurableUnlockState.Locked) }
    var unlockRemainingMs by remember { mutableLongStateOf(0L) }
    var keyConfigured by remember { mutableStateOf(lockStore.isConfigured()) }

    var editing by remember { mutableStateOf<Target?>(null) }
    var pickingApp by remember { mutableStateOf(false) }
    var pickingException by remember { mutableStateOf(false) }
    var sizingException by remember { mutableStateOf<ExceptionCandidate?>(null) }
    var showKeySetup by remember { mutableStateOf(false) }
    var startCategory by remember { mutableStateOf<UnlockCategory?>(null) }

    // Both are "quiet failure" facts, like the permission rows: nothing on screen looks wrong, and
    // the only symptom is that something has silently stopped being blocked. They read once per open
    // rather than on the 1s tick — the canary reads Instagram's version out of PackageManager, and
    // neither can change while the user is looking at this screen.
    var signalHealth by remember { mutableStateOf(SignalCanary.Health.NO_APP) }
    var omniboxHealth by remember { mutableStateOf(SignalCanary.Health.NO_APP) }
    var rulesWereCorrupt by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        signalHealth = SignalWitnessStore(context).refresh(System.currentTimeMillis())
        omniboxHealth = OmniboxWitnessStore(context).installedHealth(System.currentTimeMillis())
        // The draft's first load() is what detects and quarantines an unreadable config, so it has
        // to have run before the flag is read — asking first would always come back clean on a cold
        // start. RulesDraft.load() happens in its constructor, above.
        rulesWereCorrupt = rules.corruptBlob() != null
    }

    var blocklistRefresh by remember { mutableIntStateOf(0) }
    val sites = remember(blocklistRefresh) { blocklistStore.sites() }
    var newDomain by remember { mutableStateOf("") }
    var domainError by remember { mutableStateOf(false) }

    // One second: every countdown on every tab moves on its own rather than jumping when touched.
    LaunchedEffect(Unit) {
        var previousPhase = ""
        while (true) {
            val state = unlockController.state()
            val phase = state::class.simpleName.orEmpty()
            unlockState = state
            unlockRemainingMs = when (state) {
                is DurableUnlockState.Pending ->
                    DurableUnlockManager.msUntilOpen(state, SystemClock.elapsedRealtime())
                is DurableUnlockState.Open ->
                    DurableUnlockManager.msUntilClose(state, SystemClock.elapsedRealtime())
                else -> 0L
            }
            statuses = coordinator.snapshot()
            tamperReason = coordinator.tamperReason()
            autoTime = integrity.autoTimeEnabled()
            serviceRunning = AppBlockerAccessibilityService.isRunning
            keyConfigured = lockStore.isConfigured()
            now = clock.nowLocal()
            rules.reload()
            // A message about a phase must not outlive that phase: "wait started" is a lie the
            // second the window opens, and the tick can cross that boundary at any moment.
            if (previousPhase.isNotEmpty() && phase != previousPhase) message = null
            previousPhase = phase
            delay(1_000)
        }
    }

    val appsWindowOpen = DurableUnlockManager.isOpenFor(unlockState, UnlockCategory.APPS)
    val websitesWindowOpen = DurableUnlockManager.isOpenFor(unlockState, UnlockCategory.WEBSITES)
    val statusesByTarget = statuses.associateBy { it.target }
    val logicalDay = DayBoundary.logicalDay(now)
    val countdown = formatHmsFromMs(unlockRemainingMs)

    // No key means no window can ever open — `LockStore.verify` refuses everything while nothing is
    // stored — so a keyless phone is *stricter* than a locked one, not looser. Quoting the 2 h price
    // here would name a cost that cannot be paid; the resting line says what is actually true.
    val lockLine = when {
        !keyConfigured -> stringResource(R.string.lock_line_nokey)
        unlockState is DurableUnlockState.Pending -> stringResource(R.string.lock_line_pending, countdown)
        unlockState is DurableUnlockState.Open -> stringResource(R.string.lock_line_open, countdown)
        else -> stringResource(R.string.lock_line_locked)
    }

    val savedMessage = stringResource(R.string.lock_saved)
    val blockedSaveMessage = if (keyConfigured) {
        stringResource(R.string.lock_blocked_save)
    } else {
        stringResource(R.string.lock_blocked_save_nokey)
    }
    val savedOneChangeTitle = stringResource(R.string.lock_saved_one_change)
    val windowCancelledMessage = stringResource(R.string.lock_window_cancelled)
    val waitStartedMessage = stringResource(R.string.lock_wait_started)
    val keyCreatedMessage = stringResource(R.string.lock_key_created)
    val keyAlreadySetMessage = stringResource(R.string.lock_key_already_set)
    val exceptionCancelledMessage = stringResource(R.string.exception_cancelled)
    val removeNeedsWindowMessage = stringResource(R.string.sites_remove_needs_window)
    val exportDoneMessage = stringResource(R.string.lock_export_done)

    // Labels and scope notes have to be resolved in composition (a user-added app borrows its
    // launcher name, and the notes are string resources), so they are gathered here and handed to the
    // pure renderer as lookups.
    val exportLabels = rules.saved.targets.keys.associateWith { labelFor(it) }
    val exportScopeNotes = rules.saved.targets.keys.associateWith { target ->
        scopeNoteRes(target)?.let { stringResource(it) }
    }
    // Platform clipboard rather than Compose's LocalClipboardManager, which is deprecated in favour of
    // a suspend API this one-shot copy has no use for.
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }

    LampScaffold(
        selected = tab,
        onSelect = { selected ->
            tab = selected
            message = null
            // The receipt belongs to the visit that earned it. Leaving Lock is the acknowledgement.
            if (selected != AppTab.LOCK) receipt = null
        },
        toast = message?.let { text -> { LampToast(text) { message = null } } },
    ) {
        when (tab) {
            AppTab.TODAY -> TodayTab(
                rules = rules,
                statuses = statuses,
                usageSecondsFor = { target ->
                    UsageTracker.secondsUsedOn(engineStore.loadUsage(target), logicalDay)
                },
                historyFor = { target -> engineStore.loadHistory(target) },
                now = now,
                tamperReason = tamperReason,
                onCancelException = { target -> coordinator.cancelException(target); message = exceptionCancelledMessage },
                onRequestMoreTime = { pickingException = true },
                onOpenLock = { tab = AppTab.LOCK },
            )

            AppTab.APPS -> AppsScreen(
                rules = rules,
                statuses = statusesByTarget,
                now = now,
                lockLine = lockLine,
                onEdit = { editing = it },
                onAdd = { pickingApp = true },
                onOpenLock = { tab = AppTab.LOCK },
            )

            AppTab.SITES -> SitesScreen(
                sites = sites,
                newDomain = newDomain,
                inputError = domainError,
                windowOpen = websitesWindowOpen,
                windowCountdown = when {
                    websitesWindowOpen -> countdown
                    (unlockState as? DurableUnlockState.Pending)?.category == UnlockCategory.WEBSITES -> countdown
                    else -> null
                },
                canStartWindow = unlockState is DurableUnlockState.Locked && keyConfigured,
                onNewDomainChange = { newDomain = it; domainError = false; message = null },
                onAdd = {
                    val added = blocklistStore.add(newDomain)
                    if (added != null) {
                        newDomain = ""
                        domainError = false
                        blocklistRefresh++
                        message = context.getString(R.string.sites_blocked_toast, added)
                    } else {
                        domainError = true
                    }
                },
                onRemove = { domain ->
                    // The window is re-read at the tap rather than taken from the last recomposition
                    // — it closes on its own clock — and spent *before* the domain goes, so a
                    // process death in between costs the window, never a second free removal.
                    val open = unlockController.isOpenFor(UnlockCategory.WEBSITES)
                    if (open && blocklistStore.contains(domain)) {
                        unlockController.consume()   // single-use: one site per website window
                        blocklistStore.removeIfAuthorized(domain, true)
                        blocklistRefresh++
                        message = context.getString(R.string.sites_removed_toast, domain)
                    } else {
                        message = removeNeedsWindowMessage
                    }
                },
                onStartWindow = { startCategory = UnlockCategory.WEBSITES },
            )

            AppTab.LOCK -> LockScreen(
                state = lockStateFor(unlockState, keyConfigured, countdown, unlockRemainingMs, now),
                receipt = receipt,
                changes = rules.changes,
                direction = rules.direction,
                appsWindowOpen = appsWindowOpen,
                looseningCount = rules.looseningCount,
                protection = protectionItems(
                    accessibilityEnabled = accessibilityEnabled,
                    overlayGranted = overlayGranted,
                    autoTime = autoTime,
                    tamperReason = tamperReason,
                    serviceRunning = serviceRunning,
                    rulesWereCorrupt = rulesWereCorrupt,
                    signalStale = signalHealth == SignalCanary.Health.STALE,
                    omniboxStale = omniboxHealth == SignalCanary.Health.STALE,
                    debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                    adminActive = adminActive,
                    batteryExempt = batteryExempt,
                    notificationsEnabled = notificationsEnabled,
                    shortcutClaimed = shortcutClaimed,
                    onOpenAccessibility = onOpenAccessibility,
                    onOpenOverlay = onOpenOverlay,
                    onOpenDateSettings = onOpenDateSettings,
                    onActivateAdmin = onActivateAdmin,
                    onRequestExemption = onRequestExemption,
                    onAllowNotifications = onAllowNotifications,
                ),
                keyConfigured = keyConfigured,
                onCreateKey = { showKeySetup = true },
                onStartWindow = { startCategory = UnlockCategory.APPS },
                onCancelWindow = { unlockController.cancel(); message = windowCancelledMessage },
                onDiscard = { rules.discard(); message = null },
                onSave = {
                    val loosening = rules.loosens
                    val summary = rules.changes.firstOrNull()
                    // Re-read at the tap: `appsWindowOpen` is as old as the last recomposition, and
                    // the window closes on its own clock. The window is spent inside commit(), before
                    // the rules are written — see RulesDraft.commit for why that order.
                    val open = unlockController.isOpenFor(UnlockCategory.APPS)
                    when (val result = rules.commit(open, onLooseningAccepted = { unlockController.consume() })) {
                        is ChangeResult.Applied -> {
                        // commit() acknowledged the quarantined copy, so the row must go now rather
                        // than lingering until the next cold start.
                        rulesWereCorrupt = false
                        if (loosening) {
                            receipt = LockReceipt(
                                title = savedOneChangeTitle,
                                body = context.getString(
                                    R.string.lock_saved_one_change_body,
                                    summary?.detail.orEmpty(),
                                    formatClock(now),
                                ),
                            )
                            message = null
                        } else {
                            message = savedMessage
                        }
                        }
                        is ChangeResult.Blocked -> message = blockedSaveMessage
                        // Reachable only if the draft changed between the canSave check and the tap.
                        // The window is deliberately NOT consumed — a refused save must never cost
                        // the cycle.
                        is ChangeResult.TooManyLoosenings -> message = context.getString(
                            R.string.lock_too_many_loosenings,
                            result.loosenings.size,
                        )
                    }
                },
                onCopyRules = {
                    // Exports what is *saved*, never the draft — a record of rules that were never
                    // committed would be a record of nothing that was ever enforced.
                    val text = ConfigExport.render(
                        settings = rules.saved,
                        blockedDomains = sites.map { it.domain },
                        label = { exportLabels[it] ?: it.key },
                        scopeNote = { exportScopeNotes[it] },
                        today = LocalDate.now(),
                    )
                    clipboard?.setPrimaryClip(ClipData.newPlainText("App-Block rules", text))
                    message = exportDoneMessage
                },
                labelFor = { target -> shortLabelFor(target) },
            )
        }
    }

    // ---- sheets ----

    editing?.let { target ->
        rules.draft.targets[target]?.let { settings ->
            LimitsSheet(
                target = target,
                settings = settings,
                direction = rules.direction,
                dirty = rules.dirty,
                onWeekday = { v -> rules.update(target) { it.copy(weekdayMinutes = v) } },
                onWeekend = { v -> rules.update(target) { it.copy(weekendMinutes = v) } },
                onCeiling = { v -> rules.update(target) { it.copy(exceptionMaxMinutes = v) } },
                onSchedule = { schedule -> rules.setSchedule(target, schedule) },
                // Built-ins can only be switched off (itself a gated loosening); an app you added
                // can be dropped entirely — which the gate reads as a loosening all the same.
                onRemove = if (target.userPackage != null) {
                    { rules.remove(target); editing = null }
                } else {
                    null
                },
                onDismiss = { editing = null },
            )
        }
    }

    if (pickingApp) {
        AppPickerSheet(
            // Hide anything already covered: the curated packages, the always-blocked ones, and every
            // app already added. Offering TikTok would create a second, weaker whole-app target beside
            // the real one — and offering Shizuku would be worse than that: adding it reads as a
            // tightening while actually handing a permanently blocked package an editable rule that a
            // 2-hour window could then switch off.
            excludedPackages = AppTargets.unofferablePackages +
                rules.draft.targets.keys.mapNotNull { it.userPackage },
            onPick = { app ->
                rules.add(Target.forPackage(app.packageName), NEW_APP_DEFAULTS)
                pickingApp = false
                message = context.getString(R.string.apps_added, app.label)
            },
            onDismiss = { pickingApp = false },
        )
    }

    if (pickingException) {
        ExceptionPickSheet(
            candidates = statuses.map { it.toExceptionCandidate() },
            onPick = { candidate ->
                pickingException = false
                sizingException = candidate
            },
            onDismiss = { pickingException = false },
        )
    }

    sizingException?.let { candidate ->
        ExceptionAmountSheet(
            candidate = candidate,
            windowMinutes = rules.saved.exceptionWindowMinutes,
            waitMs = ActiveRules.exceptionWaitMs,
            now = now,
            onConfirm = { extra ->
                coordinator.requestException(candidate.target, extra, rules.saved.exceptionWindowMinutes)
                sizingException = null
            },
            onDismiss = { sizingException = null },
        )
    }

    if (showKeySetup) {
        KeySetupSheet(
            onConfirm = { generated ->
                val stored = lockStore.setKey(generated)
                keyConfigured = true
                showKeySetup = false
                message = if (stored) keyCreatedMessage else keyAlreadySetMessage
            },
            onDismiss = { showKeySetup = false },
        )
    }

    startCategory?.let { category ->
        StartWindowSheet(
            category = category,
            verify = { code -> lockStore.verify(code) },
            onVerified = {
                unlockController.request(category)
                startCategory = null
                tab = AppTab.LOCK
                message = waitStartedMessage
            },
            onDismiss = { startCategory = null },
        )
    }
}

/** Today's own assembly, kept beside the screen it feeds rather than inside the root's body. */
@Composable
private fun TodayTab(
    rules: RulesDraft,
    statuses: List<TargetStatus>,
    usageSecondsFor: (Target) -> Long,
    historyFor: (Target) -> List<DayUsage>,
    now: java.time.LocalDateTime,
    tamperReason: String?,
    onCancelException: (Target) -> Unit,
    onRequestMoreTime: () -> Unit,
    onOpenLock: () -> Unit,
) {
    val logicalDay = DayBoundary.logicalDay(now)
    // Today reads the *saved* settings, never the draft: it answers "how am I doing against the
    // rules in force", which an unsaved edit has not changed.
    val rows = todayRows(
        settings = rules.saved,
        statuses = statuses,
        usageSecondsFor = usageSecondsFor,
        today = logicalDay,
        todayDayOfWeek = logicalDay.dayOfWeek,
    )
    val exception = statuses.firstNotNullOfOrNull { status ->
        when (val state = status.exception) {
            is ExceptionState.None -> null
            is ExceptionState.Pending -> TodayException(
                target = status.target,
                extraMinutes = state.extraMinutes,
                active = false,
                remainingMs = status.exceptionActivatesInMs ?: 0L,
                raisedCapMinutes = (status.normalCapMinutes + state.extraMinutes)
                    .coerceAtMost(status.exceptionMaxMinutes),
                windowMinutes = state.windowMinutes,
            )
            is ExceptionState.Active -> TodayException(
                target = status.target,
                extraMinutes = state.extraMinutes,
                active = true,
                remainingMs = status.exceptionEndsInMs ?: 0L,
                raisedCapMinutes = status.effectiveCapMinutes,
                windowMinutes = rules.saved.exceptionWindowMinutes,
            )
        }
    }

    TodayScreen(
        now = now,
        logicalDay = logicalDay,
        rows = rows,
        remainingSeconds = statuses.sumOf { it.remainingSeconds },
        usedMinutes = (statuses.sumOf { it.usedSeconds } / 60L).toInt(),
        capMinutes = statuses.sumOf { it.effectiveCapMinutes },
        closedCount = rows.count { it.state == TargetState.SPENT || it.state == TargetState.CLOSED },
        exception = exception,
        tamperReason = tamperReason,
        week = weekRows(
            settings = rules.saved,
            today = logicalDay,
            historyFor = historyFor,
            todaySecondsFor = usageSecondsFor,
        ),
        onCancelException = { exception?.let { onCancelException(it.target) } },
        onRequestMoreTime = onRequestMoreTime,
        onOpenLock = onOpenLock,
    )
}

private fun TargetStatus.toExceptionCandidate(): ExceptionCandidate = ExceptionCandidate(
    target = target,
    capMinutes = normalCapMinutes,
    ceilingMinutes = exceptionMaxMinutes,
    existingPhase = when (exception) {
        is ExceptionState.Pending -> ExceptionPhase.PENDING
        is ExceptionState.Active -> ExceptionPhase.ACTIVE
        is ExceptionState.None -> null
    },
)

private fun lockStateFor(
    state: DurableUnlockState,
    keyConfigured: Boolean,
    countdown: String,
    remainingMs: Long,
    now: java.time.LocalDateTime,
): LockState {
    if (!keyConfigured) return LockState.NoKey
    return when (state) {
        is DurableUnlockState.Locked -> LockState.Locked
        is DurableUnlockState.Pending -> {
            // The controller's own function, not a copy of it: this used to be mirrored here, and a
            // mirror is how the progress track ends up drawing a 2-hour wait the controller is
            // running in two minutes.
            val total = DurableUnlockController.waitMsFor(state.category)
            LockState.Pending(
                category = state.category,
                countdown = countdown,
                elapsedFraction = if (total <= 0L) 0f else ((total - remainingMs).toFloat() / total),
                // Derived from the deadline rather than stored: the wait is monotonic, so the only
                // honest wall-clock statement about it is "this many milliseconds either side of now".
                startedAt = formatClockIn(now, -(total - remainingMs)),
                opensAt = formatClockIn(now, remainingMs),
            )
        }
        is DurableUnlockState.Open -> LockState.Open(state.category, countdown)
    }
}

/**
 * Limits a newly added app starts with. Deliberately a real cap rather than 0: adding at any cap is a
 * tightening (the app had no limit at all a moment ago), and it can be dropped to 0 for free —
 * whereas a default of 0 that felt too harsh would cost a 2-hour window to relax.
 */
private val NEW_APP_DEFAULTS = TargetSettings(
    enabled = true,
    weekdayMinutes = 30,
    weekendMinutes = 30,
    exceptionMaxMinutes = 60,
)
