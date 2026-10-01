# Overnight goal — full brief

Build AgentCraft to the v1 "definition of done" in docs/SPEC.md, meeting the visual bar in
docs/visual-bar.md. Blendi is asleep. Work autonomously all night with ultracode on, using
workflows and as many tokens as needed. The main session is the orchestrator: one workflow
per phase, read its results, verify them yourself, then start the next.

## What it is
Minecraft (Fabric 1.21.x, run via `gradlew runClient`, no launcher) as a spatial UI for real
multi-agent Claude work. A Node/TS "Foreman" orchestrator (Claude Agent SDK) runs lead + worker
agents in real git worktrees, with a task graph, message bus, shared memory, and a decision
queue. The mod renders it all physically: HQ building, agent NPCs with skins walking between
stations, monitors streaming logs, a Task Wall, a Decision Podium, a diff review/merge station,
a memory library, and a command console. Blendi wants to use this **for actual work**, so
practicality matters as much as looks: console-first fast input, readable diffs, durable state,
Windows toasts when Blendi is needed, a permissions flow, and one-command launch.

## This is a direction, not a rigid plan
The spec, phases, protocol, art direction and feature list are a strong starting point, **not
a contract**. If something doesn't seem right once you're building it — a design that's clunky
to use, a visual that doesn't land, a technical approach that fights Minecraft/Fabric, a
protocol shape that's awkward, a feature that adds noise instead of clarity — **try
alternatives**. Prototype 2–3 options, screenshot or test them side by side, keep the one that's
actually better, and note the change and why in STATUS.md (and update SPEC.md /
visual-bar.md so they stay the source of truth). Invent better systems if you see them. Judge by
two questions: "would Blendi actually use this for real work every day?" and "does it look
fantastic?" The only fixed points are the Rules below and the spirit of the Done criteria.

## Phases (adjust freely, but keep each one verifiable)
1. **Foundation**, in parallel: (a) the Fabric mod builds and runClient quick-plays into a
   world, with a DevBridge (dev WS: camera + screenshot + commands) proven by a real PNG;
   (b) the Foreman, with the protocol, `sim` and `claude` backends, persistence, and vitest
   tests; (c) the art pipeline: palette, procedural textures, hand-quality 64x64 agent skins,
   block/item models.
2. **Integration**: the mod connects to the Foreman, snapshot/upsert rendering works, agent
   entities exist, `tools/launch.ps1` works, and `tools/shoot.mjs` + `tools/scenes/qa.json` exist.
3. **Features**, in parallel via worktrees then merged: HQ builder, agent entities + pathing +
   anims + nameplates + particles, monitors, Task Wall, Decision Podium + GUI, console screen,
   diff/merge screen, memory library, HUD/boss bar, notifications, permissions.
4. **Real end-to-end**: the `claude` backend runs a real goal on a sandbox repo in sandbox/
   (lead plans, ≥2 workers, an ask_user decision, a merge review). Also verify the restart and
   Foreman-kill recovery paths.
5. **Polish loop**: run the full QA scene set, have independent judges score it against the
   rubric, fix the lowest scores, and repeat until every score is ≥8 on every shot. Then a
   practicality pass: README, launch script, cold-start test from a clean clone, and actually
   *use* it for a realistic task to find friction.

## Rules (fixed)
- **Verify for real**: build it, run it, screenshot it, and Read the PNGs. Never claim something
  works or looks good without in-game proof. Shots go to artifacts/shots/.
- **Git**: commit in C:\Projects\agentcraft after each verified step. Local only, never push.
  Keep docs/STATUS.md updated with what's done, what's verified (with screenshot paths), what
  changed from the plan and why, and what's next.
- **Auth**: headless `claude -p` was verified working at the start of the night. If it breaks,
  carry on with the `sim` backend, keep the claude backend tested as far as possible, and record
  what remains. Never enter credentials yourself.
- **Safety**: agents only operate on repos in sandbox/ during testing. Never touch Blendi's other
  repos in C:\Projects. Never push, never delete outside the project.
- **Another agent is working in parallel** on C:\Projects\bo1-skyrim and bo1-skyrim-web. Don't
  read, modify, or run anything there. Don't kill java/node/browser processes you didn't start
  (track your PIDs). Avoid common dev ports (3000, 5173, 8080). Use 7878/7879 and make them
  configurable. No global installs or system/global config changes. Take screenshots only through
  the mod's DevBridge, never by capturing the desktop or stealing window focus. Run one Minecraft
  client at a time unless more is truly needed. We share the GPU/CPU and API rate limits.
- **Decisions**: if a decision is genuinely Blendi's, pick the sensible default, log it in
  STATUS.md under "Decisions for Blendi", and keep going. Never stop and wait.

## Done when
Everything in SPEC.md's v1 definition of done (as it stands after any improvements you made) is
verified with screenshots and tests, every QA shot scores ≥8 on every rubric axis from
independent judges, `tools/launch.ps1` works from a cold start, and STATUS.md opens with a
morning summary: what works, how to run it, the screenshot gallery, what changed from the
original plan and why, known issues, and pending decisions for Blendi.
