# App-Block

Personal, self-built Android app blocker — a commitment device designed to be hard to bypass in a moment of impulse. Sideloaded, not a Play Store app. The threat model is the owner's own future self, not an attacker.

Kotlin + Jetpack Compose · compileSdk/targetSdk 36, minSdk 26 · daily driver is a Galaxy S25 FE (One UI 8 / Android 16).

## Status — budgets, website blocking, durable-change lock, self-defense
- **Detection:** `AccessibilityService` + a `getWindows()` scan catch a budgeted app in *any* visible pane (split-screen / App Pairs). Instagram is split by surface — Reels & Explore are metered via the reel pager's view ids, the app as a whole only carries closing hours. Lite clients count against the same target as the full app. Any installed app can be added from an on-device picker.
- **Engine (pure Kotlin, JVM-tested):** daily per-app caps (weekday/weekend), 4 am logical-day reset, bounded "exception" raises behind a monotonic wait, per-app time-of-day schedules, schedule-only rules.
- **Website blocking:** a domain blocklist read from the address bar of allowlisted browsers (Chrome, Brave); every other browser is blocked as an app; WebAPKs are caught by package prefix. A version-keyed "vouch" keeps an unreadable address bar from blocking ordinary browsing, and a drift canary says when a browser update has made it unreadable for a week.
- **Clock tamper guard:** turning automatic date/time or time zone off latches a block-everything state the moment it happens (a ContentObserver, not the next tick), and it clears only once both are back on *and* the clock agrees with where the OS last had it — so toggle-off / change / toggle-on between two passes gains nothing. Within a boot the logical day can't advance faster than uptime, whatever the wall clock says; a date rollback re-keys onto the larger of the two days' counts.
- **Durable-change lock:** rules are editable but asymmetric — tightening is always free; loosening needs the stashed QR key → a wait (2 h for apps, 72 h for websites) → a 15-minute single-use window that buys exactly **one** change. A target's caps and hours are guarded whether or not its switch is on. Only a salted hash of the key is stored.
- **Self-defense:** Settings screens *about* App-Block (its Accessibility toggle, App info, the per-app overlay page, the device-admin page, the uninstall dialog, wireless-debugging pairing) bounce to Home unless the change window is open; lists that merely contain the app's name are left alone. The service requests the accessibility button so no accessibility shortcut can toggle it off.
- **Device admin with zero policies:** being an active admin is what stops One UI Modes suspending the package. Activation is offered in-app; deactivation is bounced and, if it happens anyway, nagged about.
- **Block screen:** `SYSTEM_ALERT_WINDOW` overlay with reason-aware messages; kick-to-home fallback if the overlay permission is revoked.
- **Watchdog:** a 15-minute worker (re-run on demand while Settings is on screen) reports the service being disabled or dead, the overlay gone, the device admin deactivated, the battery exemption removed — each as an ongoing notification withdrawn when fixed — and the Lock tab lists every one of those plus notifications themselves, each with a one-tap repair.
- State lives in SharedPreferences with backups excluded; the saved rules can be exported as prose to the clipboard.

Still the software-friction tier: safe mode, a factory reset and adb from a computer defeat it by design. The optional Device Owner hardening tier remains the possible next step.

## Build
- Requirements: JDK 17 + Android SDK (platform 36). Android Studio (Meerkat Feature Drop or newer, for AGP 8.10) optional.
- CI: every push runs `testDebugUnitTest`, `lintRelease` (errors fail the job; the report is uploaded) and
  builds both APKs ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)); a green run attaches `app-debug.apk`
  as a workflow artifact — downloadable and sideloadable straight from the GitHub mobile app, no laptop. The
  release build on CI is unsigned and not uploaded; it exists to prove R8 from a clean clone. Actions are
  SHA-pinned, with Dependabot (monthly, grouped) keeping the pins current.
- CLI: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`. The Gradle wrapper is committed (checksum-pinned, with dependency verification metadata), so no separate Gradle install is needed.
  - Create a `local.properties` with `sdk.dir` pointing at your Android SDK (gitignored; per machine).
- Variants: `debug` (`com.appblock.debug`, debuggable, what CI publishes) · `debugFast` (`com.appblock.fast`, 1-minute caps for QA, non-debuggable) · `release` (`com.appblock`, R8-minified, the daily driver).
- Release: `./gradlew assembleRelease` signs only if `keystore.properties` + the `.jks` are present at
  the repo root (both gitignored — private keystore, deliberately with no synced copy); without them the release APK builds unsigned.
- Tests: `./gradlew testDebugUnitTest --rerun` — pass `--rerun`, because an up-to-date run reports success without executing anything. 536 JVM tests (engine + Robolectric screens/stores/workers) at the time of writing.

## Install (sideload)
1. Install the APK (`adb install -r`, or Android Studio Run).
2. Android 13+ blocks Accessibility for sideloaded apps: Settings → Apps → App-Block → ⋮ → "Allow restricted settings".
3. In the app's Lock tab: grant Accessibility + "Display over other apps", activate the protection admin, grant the battery exemption, allow notifications — every row shows its own repair button until it is green.
4. Create the lock key only once the rules are right: it is one-shot, and there is no in-app re-key.

## Layout
- `app/src/main/java/com/appblock/`
  - `MainActivity.kt` — reads the special grants on every resume and hands them to the UI
  - `ui/` — Compose: `AppRoot` (one clock, one draft, one unlock cycle above four tabs), the Today / Apps / Sites / Lock screens, the limits and exception sheets, the key setup and unlock sheets
  - `ActiveRules.kt` — picks real caps vs the `debugFast` variant's fast QA values
  - `engine/` — pure-Kotlin engine (policy, usage, exceptions, schedules, day boundary, tamper-guard
    inputs, durable-change gate + unlock state machines, settings-watch decision, browser policy,
    Instagram surface, address watch, drift canaries, codec, store interfaces) — no Android imports
  - `security/` — Android side of the lock: key hashing + storage, QR render, unlock controller, blocklist store
  - `service/` — the live blocker: accessibility service + overlay + settings-watch, the device-admin
    receiver, real clocks (`AndroidEngineClock`, `AndroidClockIntegrity`), watchdog + unlock-window workers
  - `data/` — SharedPreferences-backed stores (engine state, durable rules, installed apps, the canaries' witnesses)
  - `util/Permissions.kt` — reads + repair intents for every special grant
- `app/src/test/` — the JVM suite; screen tests run in the `debug` variant only

## Process & reuse
- [PLAYBOOK.md](PLAYBOOK.md) — how this project is planned and tracked: gated batches, the
  progress-map visual, the docs split, and the template patterns worth reusing.
- This repo doubles as the **base/template for future apps** — fork it and keep the playbook.

## License
Personal project — no license granted yet. Add one if you want others to reuse it.
