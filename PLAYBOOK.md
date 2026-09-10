# PLAYBOOK — how this repo is planned, tracked, and reused

Working practices that proved out on App-Block. **This repo doubles as the base/template for future
apps** — fork or build off it and carry these practices forward.

## Planning: the gated plan
- The TODO is one ordered list of **batches** and **gates**, worked top-to-bottom:
  - 🖥️ **batch** = autonomous desktop work, zero decisions inside; exit criteria stated up front
  - 🚪 **gate** = needs the owner: a device session, a spec decision, a judgment call
- A batch never stalls mid-way on a question — anything needing input is hoisted to the nearest gate,
  so owner involvement collapses to a few well-defined checkpoints.
- Decisions made at a gate get recorded inline (date + choice) and the gate flips to ✅ decided.
- Done items leave the TODO for a STATUS doc ("what works") — the TODO stays purely forward-looking.

## Progress map (the visual)
Re-render **every time tasks finish** — a vertical spine of stages with a green sidebar that fills
as items complete. Cute, glanceable, keeps momentum visible.

- SVG, viewBox ~680×720, themed via CSS variables (works light + dark):
  - Spine boxes: x=180 w=240, rows 56 tall, 36 gap, first row at y=40; one box per batch/gate.
  - Side boxes (feeder gates / parked tracks): x=444 w=196; arrows centered at x=300.
  - Colors: **purple** = autonomous batch · **amber** = owner gate · **green** = done/decided ·
    **gray dashed** = parked.
- Sidebar (the progress bar):
  - Track: `<rect x="120" y="40" width="14" rx="7">` spanning first row top → last row bottom,
    surface fill + hairline border.
  - Each stage owns the vertical band of its row ± half the gap to neighbors; 0.5 px ticks at band
    edges.
  - Green fill per stage: height = (items done / items total) × band height, anchored at band top,
    clipped to the track's rounded rect via `<clipPath>`.
  - Current stage: 2 px amber ring around its band ("you are here"); overall % label under the
    track, weighted by item counts.
  - Legend: purple = autonomous · amber = needs you · green bar = done · ring = you are here.
- A small state table (stage | done/total | status) lives beside the TODO and drives the fill;
  recount whenever the TODO changes.

## Running a hardware gate: phase it by build variant

A device gate is usually planned as "install the build, walk the list". That breaks as soon as the
list gets long, because **three things you need are properties of different builds**:

1. **It's the artifact that ships** — signed with the real key, real timings, minified.
2. **It's diagnosable** — emits the internal state you need to tell a pass from a lucky pass.
3. **It's fast to exercise** — hours-long waits compressed to seconds.

(1) actively excludes (2) and (3): a release build that logged its decisions would be leaking, and
one with test timings isn't the thing you're shipping. So stop trying to find one build that does
all three and **split the session into phases, one per variant.**

**Give the QA variant its own `applicationIdSuffix`** so it installs *alongside* the real app rather
than over it. This is the move that makes phasing cheap:
- no uninstall, so **no config wipe** — state-migration checks stay runnable
- the QA build can never inherit the real app's granted permissions via an in-place update, which
  matters a lot if those grants are the thing under test

**Order the phases by what can't be replayed.** Anything observable only *once* — a migration from
the previously-installed version, a first-launch path, a one-way state change — goes first, before
anything else perturbs the state it reads. Everything else can be re-run.

**Tag every checklist item with its phase** (`[P1]`, `[P2]`, …). An untagged list silently assumes
one build and you find out at the phone.

**Write the costs into the checklist, not just the plan:**
- the QA variant needs its **own** permission grants and its own first-run setup — budget the minutes
- **two instances of the app now run at once**, often with the same label. Attribute behavior by log
  output, not by looking at the UI; only one of them logs
- if the app defends itself, **disabling the real instance to isolate the QA one is itself a guarded
  action** — don't plan a test that requires spending a real unlock to run
- **capture any state you'd hate to re-enter before phase 1** — if the app can export its config,
  that export is both a checklist item and the session's insurance

## Post-install: undo the shortcuts the OS grants itself

**Installing an accessibility service can hand it privileges nobody asked for, and the ones that
matter are the ones that switch it *off*.** On One UI 8 the install alone adds the service to
`accessibility_button_targets`; with gesture navigation the system then forces
`accessibility_button_mode=1` and draws a floating pill on the screen edge. Long-press that pill →
Edit → untick the service, and `enabled_accessibility_services` is empty: four taps, no key, no
computer, and nothing notices, because the watchdog runs inside the service that just died.
`accessibility_gesture_targets` is claimed the same way. (Measured on the S25, 2026-08-29.)

So a device install is not finished when the APK lands. Clear both targets:

```bash
adb shell "settings put secure accessibility_button_targets ''"
adb shell "settings put secure accessibility_gesture_targets ''"
```

Note the quoting: the empty argument has to survive the *local* shell, so the whole command is
quoted for the device. `adb shell settings put secure accessibility_button_targets ""` loses it and
fails with `Bad arguments` — while still looking like it ran.

Three things generalize past this app:
- **The OS's defaults are part of the threat model.** Nothing in the manifest asked for the pill.
  It was found by reading the device back (`settings get secure …`, `dumpsys accessibility`), and
  the code comment it replaced had asserted the opposite, in good faith, for weeks.
- **Don't disarm the declaration to fix the state.** `flagRequestAccessibilityButton` is *why* every
  soft shortcut becomes a no-op callback instead of toggling the service off. Dropping it to lose
  the pill re-opens the volume-key chord — a worse door, reachable in two seconds.
- **Re-run it after every install.** The installer makes the claim, so the claim comes back. This
  belongs on the post-install checklist beside the permission grants, not in someone's memory.

## Docs split
- **Repo (public):** README (what + how to build), code, this playbook. Nothing personal.
- **Private planning folder (synced, outside git):** STATUS (current state, lean) · TODO (the gated
  plan) · CONSTRAINTS (rules worksheet) · MEMORY (cross-session working memory) · ARCHIVE (parked
  detail) · the progress-map state table.
- **Never commit:** keystores/credentials (gitignored), personal config or blocklists, anything
  identifying.

## Template bits worth reusing in the next app
- **Pure-JVM engine module**: all logic under `engine/` with zero Android imports → whole behavior
  spec runs as fast JVM unit tests, no emulator.
- **Store interfaces in the engine, Android impls in `data/`** → tests swap in in-memory fakes.
- **`debugFast` build variant**: real code, compressed timings behind a `FAST_CAPS` flag, own
  `applicationIdSuffix` → installable next to the real app for on-device QA. Gate diagnostic logging
  on `DEBUG || FAST_CAPS` so the shipped build stays silent. See "Running a hardware gate" — the
  suffix is what makes a phased device session cheap.
- **Release signing**: gitignored `keystore.properties` + `.jks`, graceful unsigned fallback when
  absent — builds work on any machine, signs only where the key lives.
- **Backtick JUnit test names** as a living spec (`` `reboot mid-wait restarts the clock` ``).
