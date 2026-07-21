# App-Block

Personal, self-built Android app blocker — a commitment device designed to be hard to bypass in a moment of impulse. Sideloaded, not a Play Store app.

Kotlin + Jetpack Compose · targets Android 15 (One UI 7).

## Status — MVP (v0.1)
- `AccessibilityService` detects when a blocked app comes to the foreground
- `SYSTEM_ALERT_WINDOW` overlay draws a full-screen "blocked" screen over it
- Compose config UI: permission-status card + launchable-app picker
- Blocked list persisted in SharedPreferences

Deliberately weak at this stage — force-stop, reboot, or uninstall all defeat it. A friction layer (unlock cooldowns, delayed settings changes, externally-held passphrase) and an optional Device Owner hardening tier are the next steps.

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
  - `MainActivity.kt` — Compose config UI (permission status + app picker)
  - `service/AppBlockerAccessibilityService.kt` — foreground detection + overlay block screen
  - `data/` — blocked-app repository, installed-apps provider
  - `util/Permissions.kt` — special-permission checks + Settings intents

## License
Personal project — no license granted yet. Add one if you want others to reuse it.
