# App-Block

Personal, self-built Android app blocker — a commitment device designed to be hard to bypass in a moment of impulse. Sideloaded, not a Play Store app.

Kotlin + Jetpack Compose · targets SDK 35 · daily driver is a Galaxy S25 FE (One UI 8 / Android 16).

## Status — Tier-2 budgets + durable-change lock
- `AccessibilityService` + window scan detect when a budgeted app is visible (any split-screen pane)
- Pure-Kotlin budget engine: daily per-app caps (weekday/weekend), 4am logical-day reset, bounded
  "exception" raises gated by a monotonic wait, per-app time-of-day schedules, all JVM-unit-tested
- Clock tamper guard: with automatic date & time off, any manual clock change (or a reboot) latches a
  block-everything state until automatic time is back on; date rollbacks can't re-grant a spent day
- Durable-change lock: rules are editable but asymmetric — tightening is free, loosening needs a
  stashed QR key → delayed single-use change window (only a salted hash of the key is stored)
- Self-defense settings-watch: Settings screens about App-Block itself (its Accessibility toggle,
  App info) bounce to Home unless the change window is open
- `SYSTEM_ALERT_WINDOW` overlay block screen (reason-aware messages), kick-to-home fallback if the
  overlay permission is revoked mid-session; watchdog notification if the blocking service dies
- State in SharedPreferences (backup/restore excluded); Compose status, settings + unlock UI

Still the software-friction tier — force-stop or uninstall defeat it. The optional Device Owner hardening tier is the possible next step.

## Build
- Requirements: JDK 17 + Android SDK (platform 35). Android Studio (Ladybug or newer) optional.
- CLI: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`. The Gradle wrapper is committed, so no separate Gradle install is needed.
  - Create a `local.properties` with `sdk.dir` pointing at your Android SDK (gitignored; per machine).
- Release: `./gradlew assembleRelease` signs only if `keystore.properties` + the `.jks` are present at
  the repo root (both gitignored — private keystore); without them the release APK builds unsigned.

## Install (sideload)
1. Install the debug APK (`adb install`, or Android Studio Run).
2. Android 13+ blocks Accessibility for sideloaded apps: Settings → Apps → App-Block → ⋮ → "Allow restricted settings".
3. In the app: grant Accessibility + "Display over other apps", then tick apps to block.

## Layout
- `app/src/main/java/com/appblock/`
  - `MainActivity.kt` / `SettingsScreen.kt` — Compose UI: per-target status, exception requests,
    gated rule editing, key setup + unlock flow
  - `ActiveRules.kt` — picks real caps vs the `debugFast` variant's fast QA values
  - `engine/` — pure-Kotlin engine (policy, usage, exceptions, schedules, day boundary, tamper-guard
    inputs, durable-change gate + unlock state machines, settings-watch decision, codec, store
    interfaces) — no Android imports, fully JVM-testable
  - `security/` — Android side of the lock: key hashing + storage, QR render, unlock controller
  - `service/` — the live blocker: accessibility service + overlay + settings-watch, real clocks
    (`AndroidEngineClock`, `AndroidClockIntegrity`), watchdog + unlock-window workers
  - `data/` — SharedPreferences-backed stores (engine state, durable rules)
  - `util/Permissions.kt` — special-permission checks + Settings intents

## License
Personal project — no license granted yet. Add one if you want others to reuse it.
