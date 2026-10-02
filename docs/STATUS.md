# STATUS

## Summary (2026-10-01, wrap-up)

**State:** v1 is functionally complete and merged on `main`. Every major system works together
in-game, with both the sim backend and **real Claude agents**: the e2e run went fully through the
game UI. A cold start from a fresh clone works in 43 s. Blendi asked to finish rather than run the
visual polish and judge loop, so those were skipped. See "Not done" below.

### SPEC v1 definition of done, line by line
| DoD line | Status | Evidence |
|---|---|---|
| `tools/launch.ps1` → game opens into the HQ, Foreman connected, banner shows backend status | ✅ verified | launch output (connected, "Foreman · sim/claude" pill); cold start from a fresh clone in 43 s: docs/img/coldstart_atrium.png |
| Goal via console, claude backend, sandbox repo: lead plans, ≥2 workers in worktrees, ask_user answered in-world, tasks move on the Task Wall, monitors stream logs, merge reviewed in-game | ✅ verified | docs/img/e2e/: e2e_05 decision GUI (Marlow's real question), e2e_08 task wall, e2e_10 diff review, e2e_19 monitor streaming; sandbox/demo-app main has 6 merged agentcraft/* branches, 49 tests pass |
| Same flow fully works with the `sim` backend | ✅ verified | artifacts/shots/qa/orch-p3/ (all 10 QA shots from the sim showcase); live sim runs in Phase 2/3 verifier logs |
| Screenshot QA suite passes the visual bar review | ❌ not met | qa.mjs run orch-p3: 10/10 shots captured. Two independent judges: **no shot scores ≥8 on every axis** (table below) |
| Restart the game mid-run → state restored; kill the Foreman → "disconnected" gracefully, reconnects | ✅ verified | real-claude run: game relaunch restored state; e2e_21 Foreman offline, e2e_23 reconnected ("Foreman · claude"), agents resumed |

### Independent judge scores (run orch-p3, before any polish; full notes and fixes in docs/qa-judges-orch-p3.json)
Axes: cohesion / composition / lighting / character / legibility / aliveness / clarity.

| Shot | Judge 1 (art director) | Judge 2 (product designer) |
|---|---|---|
| qa01 exterior hero | 7 7 5 5 5 4 3 | 6 6 5 3 5 3 3 |
| qa02 entrance atrium | 7 7 7 7 7 6 8 | 7 7 6 6 7 5 8 |
| qa03 task wall | 7 6 5 3 8 4 7 | 6 5 5 4 8 4 7 |
| qa04 agent desk | 7 6 7 6 6 7 7 | 7 6 6 6 6 7 7 |
| qa05 wide interior | 8 6 8 6 6 7 6 | 7 5 7 5 5 6 5 |
| qa06 decision podium | 6 6 6 7 7 7 6 | 7 6 6 7 7 6 7 |
| qa07 console | 7 6 7 6 8 7 8 | 7 6 6 6 8 6 8 |
| qa08 diff review | 9 8 8 6 8 6 9 | 9 8 8 6 9 7 9 |
| qa09 library | 9 8 8 5 8 6 7 | 9 8 8 6 9 6 8 |
| qa10 night | 7 7 6 4 5 5 3 | 6 6 6 3 4 4 3 |

Verdict: the diff review and library screens are near ship quality. The in-world shots are held back by:
- flat vanilla lighting (no golden-hour raking light, no hidden warm light),
- no life or status signal on the exterior and night shots,
- truncated key text (the goal, Marlow's question),
- nameplates colliding with monitors,
- the small, dim task wall,
- inconsistent status colours (t4 shows grey on the wall, clay on the nameplate).

Reaching ≥8 everywhere needs the polish pass that was skipped at Blendi's request (about 2–3 h). The judges' concrete fixes per shot are in the JSON.

### What works (verified in-game, screenshots in `artifacts/shots/qa/orch-p3/`)
- **One-command launch**: `tools\launch.ps1` starts or reuses the Foreman, builds, and opens the game
  straight into the HQ world. `tools\stop.ps1` stops only what it started. `README.md` documents it.
- **HQ**: a timber-framed studio with a cupola tower, gardens and a pond. Inside: a Goal Atrium
  (hologram + progress ring), 6 desks with seats, monitors and status lamps, a Task Wall, a library,
  a test bench, a merge station, a Decision Podium and a lounge. It auto-builds on first launch.
- **Agents**: 6 hand-made skins (Marlow, Juniper, Kit, Wren, Rowan, Tove). They walk between
  stations with A* pathing, sit at desks, show state particles, speech bubbles and decluttered
  nameplates, walk to you when they need you, and open an agent card on right-click.
- **In-world displays**: desk monitors stream each agent's live log (tool calls, diffs tinted). The
  Task Wall is a live kanban (cards with assignee, dependencies, CI badges; click for task details).
- **Console** (`` ` ``): goals, `@agent` messages with Tab autocomplete, `/answer`, `/diff`, `/pause`,
  `/stop`, `/repo add`, `/status`, `/help`, history.
- **Decisions** (`J` / podium): question, permission (with the exact "Always allow" scope) and
  merge decisions. Merge opens a **diff review screen** (file list, line numbers, scrolling) with
  Merge / Request changes / Reject.
- **Memory library**: the lead's plan and shared/per-agent notes in a two-pane reader.
- **HUD**: connection/backend pill, goal progress, "N waiting · press J" badge, toasts.
- **Foreman**: sim + claude backends, task graph, message bus, memory, decision queue, per-worker
  git worktrees, merges only on approval, persistence across restarts, reconnects, and Windows
  toasts. 465 tests. Pushes are impossible: blocked at the git level for agents, and the Foreman's
  own git calls run no hooks.

### How to run
See `README.md`. The quickest try (no API usage):
`tools\launch.ps1 -Backend sim -Reset -Speed 2`, then press `` ` `` and type a goal.

### Gallery
`docs/img/qa_contact_sheet.png` (all 10 QA shots), plus `docs/img/exterior.png`, `interior.png`,
`console.png` and `diff.png`.

### Not done / known issues (honest)
- **Real claude e2e through the game UI: done (20:00).** Goals were typed in the console, ask_user
  was answered in the decision GUI, permissions were answered in the permission GUI, and merges
  were reviewed and approved in the diff screen. That includes merge conflicts sent back to the
  worker and resolved. The game was relaunched mid-run with state restored, and the Foreman went
  offline and reconnected. Six features were merged into sandbox/demo-app main, and its 49 tests
  pass. About $6 for three goals with sonnet at low effort. Fixes from the run are merged: unknown
  Foreman flags are now errors, the conflict hand-back, empty-plan handling, and spend shown in
  `/status` and the console header. Evidence: docs/img/e2e/. Foreman: 474 tests green.
- **Polish loop not run to completion** (skipped at Blendi's request). Feature verifiers scored most shots 8-9, with some 7s on
  composition and legibility. Weak spots: the Task Wall is small and dim inside its bay, the night
  shot is too dark, some atrium/desk areas are muddy, Marlow's glasses read as sunglasses, and the
  oak parquet is busy at room scale.
- **Cold start verified** (19:40): a fresh `git clone` of main (4643119) and
  `launch.ps1 -Dev -Backend sim -Showcase busy` ran npm ci for tools and the Foreman, built the mod,
  auto-built the studio HQ, and connected to the world in **43 s** (Gradle caches shared). Evidence:
  docs/img/coldstart_atrium.png.
- **No final independent judge pass** (≥8 on every axis). It was skipped along with the visual fixes
  at Blendi's request, so the weak spots above remain.
- launch.ps1 nits from the Phase 2 verifier:
  - `AGENTCRAFT_HOME` overrides the `-Dev` home default.
  - `-Dev` without `-Backend` uses the claude backend.
  - The auth status prints before the auth check finishes.
  - npm deps are only installed when `node_modules` is missing, not when the lockfile changes.
- Identity colours form equal-lightness pairs, so they are weaker for colour-blind viewers. The
  terminal block's text is small beyond ~2.5 blocks.

### Changes from the original plan (and why)
- **Minecraft 26.3 + Java 25** instead of 1.21.x: the newest stable release, unobfuscated (real
  Mojang names), and the default in the official template.
- **Agents are client-side entities** walking a waypoint/A* graph instead of server mobs. This was
  measured: smoother, deterministic, and fine for singleplayer.
- **Protocol additions**: `foreman.status` (backend/auth banner), `ack` for client requests, and the
  `request_merge` and `list_tasks` agent tools.
- **Monitors use a dark warm screen** instead of the art track's paper screen, which read like the
  task board at mid distance.
- **Phases 4+5 were folded into a short pass, then cut** at Blendi's request.

### Decisions for Blendi (defaults chosen)
- Claude defaults: lead = Opus, workers = Sonnet, medium effort, max 3 workers, lead review on.
  Cheaper: `--model sonnet --effort low`.
- Approved merges are made as you, and signed if your git config signs. The alternative is
  `--merge-style squash`.
- Agent branches `agentcraft/*` are kept forever (no auto-cleanup yet).
- Agents have no git network access at all, so fetch is blocked too. That's the strongest
  guarantee against pushes.
- The OS temp dir is free scratch space for agents (no prompts).
- The HQ world is a superflat meadow, fixed at golden hour (time 12000).

## Log
- 2026-10-01 night: spec + visual bar + GOAL.md written; claude CLI auth verified.
- Phase 1 (foundation), wf_6e6eace6-b3a: mod+DevBridge / Foreman / art in parallel with adversarial verify loops.
- 11:40 Phase 1 result:
  - Mod passed verify round 2.
  - Foreman: 4 verify rounds hardened the permission policy; the last finding (repo hooks running in the Foreman's own git calls) was fixed by the orchestrator, with a regression test.
  - 465 tests green.
  - Committed 5bf1d38.
- 13:30 Art passed verify after an in-game check. Committed e67ab16.
- Lesson: the verify/fix loops were too slow (Phase 1 took ~7h). Later phases were capped.
- ~14:00 Phase 2 passed:
  - Core: Foreman link, blocks, NPCs, anchors, FEATURES.md.
  - Tools: launch/stop/qa.
  - Orchestrator re-verified it (orch_p2_*.png).
  - Commits ea00efc, a34f624.
- Phase 3, wf_08436cd8-0fa: HQ / agent life / displays / console+decisions+HUD / diff+library in worktrees.
- 18:20 Blendi asked to go faster: merge finished features right away and skip extra verify rounds.
- 18:25 Merged HQ, agent life and displays. The integrated check looks good (orch_p3_cam_*.png).
- 19:10 Merged review and console. Resolved the PlateLayout conflict by hand. Integrated QA run 10/10 (qa/orch-p3).
- 19:20 Final pass (e2e / visual / practicality) started, then stopped at Blendi's request to wrap up. Cleaned up every process it left. Wrote README.md and this summary.
- 19:40 Cold start from a fresh clone verified (43 s).
- 20:00 The in-game real-claude e2e finished its flow. Visual fixes were skipped at Blendi's request. The orchestrator verified the e2e fixes (tsc, 474 tests, mod build, demo-app tests), merged them (5222584), and passed a final smoke launch (artifacts/shots/final_smoke.png).

