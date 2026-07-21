# App-Block

Personal, self-built Android app blocker — a commitment device designed to be hard to bypass in a moment of impulse. Sideloaded, not a Play Store app.

Kotlin + Jetpack Compose · targets Android 15 (One UI 7).

## Status — Tier-2 budgets (Phase 2a + hardening)
- `AccessibilityService` + window scan detect when a budgeted app is visible (any split-screen pane)
- Pure-Kotlin budget engine: daily per-app caps (weekday/weekend), 4am logical-day reset, bounded
  "exception" raises gated by a 1-hour monotonic wait, all JVM-unit-tested
- Clock tamper guard: with automatic date & time off, any manual clock change (or a reboot) latches a
  block-everything state until automatic time is back on; date rollbacks can't re-grant a spent day
- `SYSTEM_ALERT_WINDOW` overlay block screen, with a kick-to-home fallback if the overlay permission
  is revoked mid-session; watchdog notification if the blocking service dies
- State in SharedPreferences (backup/restore excluded); Compose status + exception-request UI

Still the software-friction tier — force-stop or uninstall defeat it. The QR/computer-gated durable-change lock and the optional Device Owner hardening tier are the next steps.

## Build
- Requirements: JDK 17 + Android SDK (platform 35). Android Studio (Ladybug or newer) optional.
- CLI: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`. The Gradle wrapper is committed, so no separate Gradle install is needed.
  - Create a `local.properties` with `sdk.dir` pointing at your Android SDK (gitignored; per machine).

## Install (sideload)
1. Install the debug APK (`adb install`, or Android Studio Run).
2. Android 13+ blocks Accessibility for sideloaded apps: Settings → Apps → App-Block → ⋮ → "Allow restricted settings".
3. In the app: grant Accessibility + "Display over other apps", then tick apps to block.

## Layout
- `app/src/main/java/com/appblock/`
  - `MainActivity.kt` — Compose UI: per-target status, exception requests, setup + warning cards
  - `ActiveRules.kt` — picks real caps vs the `debugFast` variant's 1-minute QA caps
  - `engine/` — pure-Kotlin budget engine (policy, usage, exceptions, day boundary, tamper guard
    inputs, codec, store interface) — no Android imports, fully JVM-testable
  - `service/` — the live blocker: accessibility service + overlay, real clocks
    (`AndroidEngineClock`, `AndroidClockIntegrity`), watchdog worker
  - `data/PrefsEngineStore.kt` — SharedPreferences-backed engine store
  - `util/Permissions.kt` — special-permission checks + Settings intents

## License
Personal project — no license granted yet. Add one if you want others to reuse it.
