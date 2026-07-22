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
  `applicationIdSuffix` → installable next to the real app for on-device QA.
- **Release signing**: gitignored `keystore.properties` + `.jks`, graceful unsigned fallback when
  absent — builds work on any machine, signs only where the key lives.
- **Backtick JUnit test names** as a living spec (`` `reboot mid-wait restarts the clock` ``).
