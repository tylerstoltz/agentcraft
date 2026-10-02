# STATUS

## Summary (2026-10-01, wrap-up)

**State:** v1 is functionally complete and merged on `main`. Every major system works together
in-game with the sim backend. Blendi asked to wrap up faster rather than finish the full polish
loop, so the final pass was cut short. See "Not done" below.

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
- **HUD**: connection/backend pill, goal progress, "N waiting Â· press J" badge, toasts.
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
- **The real claude backend has not been driven end to end through the game UI.** It was verified
  at the Foreman level in Phase 1: real SDK runs, lead plus workers, ask_user answered via the
  terminal client, worktree diffs, restart and resume. The in-game e2e run (Phase 4) was started
  and then stopped at wrap-up. **First thing to try:** `tools\launch.ps1 -Repo
  C:\Projects\agentcraft\sandbox\demo-app` (after `node sandbox/create-demo.mjs --force`).
- **Polish loop not run to completion.** Feature verifiers scored most shots 8-9, with some 7s on
  composition and legibility. Weak spots: the Task Wall is small and dim inside its bay, the night
  shot is too dark, some atrium/desk areas are muddy, Marlow's glasses read as sunglasses, and the
  oak parquet is busy at room scale.
- No final independent â‰¥8-on-every-axis judge pass. No cold start from a fresh clone of the final
  `main`; the Phase 2 cold-start test of launch.ps1 passed on that era's build.
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

