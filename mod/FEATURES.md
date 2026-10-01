# AgentCraft mod: feature map for Phase 3

Read this and `mod/DEV.md` (build, run, DevBridge, 26.3 API notes). Everything below exists and
was verified in game in Phase 2 (`artifacts/shots/phase2_*.png`).

## Rules for parallel work

- **Edit only your feature package** (table below). Each feature has an `init()` that registers
  everything it needs (renderers, HUD elements, screens, keybinds, DevBridge commands, Foreman
  listeners). Central wiring is one line per feature in
  `src/client/java/dev/agentcraft/client/ClientFeatures.java` (client) and
  `src/main/java/dev/agentcraft/AgentCraft.java` (common); both lines already exist for every feature.
- Shared packages (`foreman`, `ui`, `world`, `layout`, `block`, `entity`, `dev`) are owned by the
  core integrator. If you need something there, add it **additively** (a new method or class, never a
  changed signature) and say so in your report, or keep the helper inside your package.
- Assets (textures, models, lang, `gui/ui-style.json`, `gui/kit.json`, `cast.json`) come from
  `assets-src/` via `python assets-src/sync.py`. Don't hand-edit `mod/src/main/resources/assets`.
- Minecraft is **26.3** with Mojang names. Grep `mod/build/mcsrc` (`gradlew mcSources`) for real
  signatures; don't code from 1.21 memory.
- Verify visually: `dev.camera {anchor:"cam_..."}` + `dev.screenshot`, then Read the PNG.

## Packages

| package | owner (Phase 3) | owns |
|---|---|---|
| `client.foreman` | core | WebSocket link, protocol mirror, state model, `Foreman` facade |
| `client.agents` | agents specialist | agent NPCs: manager, motion, pathfinding, renderer, nameplate, hooks |
| `client.hud` | hud specialist | connection banner (done), goal boss bar, in-game toasts |
| `client.monitor` | monitor specialist | `MonitorRenderer` (BER): live agent log on desk monitors |
| `client.taskwall` | task wall specialist | `TaskBoardRenderer` (BER): kanban cards |
| `client.decisions` | decisions specialist | `DecisionPodiumRenderer` + decision GUI |
| `client.console` | console specialist | console screen + keybind, `ConsoleTerminalRenderer` |
| `client.diff` | diff specialist | diff/merge review screen, `MergeStationRenderer` |
| `client.library` | library specialist | memory screen, `MemoryArchiveRenderer` |
| `client.permissions` | permissions specialist | permission decision UX |
| `client.hq` + `hq` (main) | HQ specialist | the real HQ builder (main), world blocks driven by state (client), `StatusLampRenderer` |
| `client.ui` | core (additive) | kit drawing, style tokens, text utils (screens + world) |
| `client.world` | core (additive) | `StationRenderer` base, `ServerTasks`, `StationInteractions`, dev helpers |
| `layout` (main) | core | anchor registry + naming contract |
| `block`, `entity` (main) | core | the 16 blocks, block entities, the agent entity type |

## The Foreman state model (`dev.agentcraft.client.foreman`)

`Foreman.state()` is a `ForemanState`: the client's copy of everything the Foreman knows.
**Read and listen on the client (render) thread only** (renderers' extract step, screens, HUD, tick
handlers). From another thread use `DevBridge.onClient(mc, () -> ...)`.

```java
ForemanState s = Foreman.state();
s.agents();                 // Map<String, Protocol.Agent>, Foreman order
s.agent("kit");             // or null
s.tasks(); s.tasksWithStatus(TaskStatus.DOING);   // priority desc, then oldest
s.decisions(); s.openDecisions(); s.oldestOpen(DecisionKind.MERGE);
s.repos(); s.memory(); s.goal(); s.goals();
s.feed();                   // last 200 FeedItems, oldest first (read-only live view: copy it to keep it)
s.logs("kit");              // last 200 LogEntries of an agent (copy); s.logCount("kit") is cheap
s.lastSay("kit");           // latest agent.say (speech bubble), or null
s.notifications();          // last 20 notify messages (read-only live view)
s.status();                 // ForemanStatus: backend sim|claude, auth ok|failed|..., message, account
s.link();                   // LinkStatus: phase DISABLED|CONNECTING|HANDSHAKE|SYNCED|WAITING_RETRY, attempt, lastError, everSynced
s.isStale();                // link not live: keep showing the last known state, but dimmed / read-only
s.hasData();                // a snapshot was received at least once
s.revision();               // ++ on every change: cache derived layouts by it
```

Change callbacks (all optional, client thread, after the model was updated):

```java
Foreman.addListener(new ForemanListener() {
    public void onSnapshot(ForemanState st) { rebuildEverything(); }      // every (re)connect replaces the model
    public void onAgent(Agent prev, Agent now) { ... }                    // prev == null: new
    public void onTask(Task prev, Task now) { ... }
    public void onDecision(Decision prev, Decision now) { ... }
    public void onLog(String agentId, List<LogEntry> appended) { ... }
    public void onSay(AgentSay say) { ... }
    public void onNotify(Notify n) { ... }                                // need_user = a decision is waiting
    public void onConnection(LinkStatus l) { ... }
    public void onChange(long revision) { ... }                           // after any of the above
});
```

Intents (futures complete on the client thread; they fail fast when the link is not synced; an
`Ack` with `ok == false` carries the Foreman's error text):

```java
Foreman.submitGoal("Add OAuth to life-tracker", null).thenAccept(ack -> ...);   // ack.result().goalId
Foreman.message("kit", "please also cover emoji tags");                          // or ("all", "@kit ...")
Foreman.answer("d3", Protocol.MERGE, null);                                      // REQUEST_CHANGES + text, REJECT, ALLOW_ONCE, ALWAYS_ALLOW, DENY
Foreman.taskAction("t3", "reassign", "wren");                                    // cancel | retry | prioritize [n] | reassign <agent>
Foreman.agentAction("kit", "pause", null);                                       // resume | stop | spawn [taskId]
Foreman.addRepo("C:\\path\\to\\repo");
Foreman.requestDiff(decision.repoId(), decision.worktree()).thenAccept(diff -> ...); // Protocol.Diff, files/hunks/lines
Foreman.send("any.type", payloadJson);                                           // anything else in docs/protocol.md
```

`Protocol` mirrors `docs/protocol.md` exactly (records + enums). Optional fields are nullable,
required lists are never null, unknown enum values map to `UNKNOWN`, unknown fields are ignored.
`AgentState.family()` gives the status family (idle thinking working waiting error done) used for
dots, lamps and colours. Exact option labels: `Protocol.MERGE`, `REQUEST_CHANGES`, `REJECT`,
`ALLOW_ONCE`, `ALWAYS_ALLOW`, `DENY`.

Link details: `ws://127.0.0.1:${AGENTCRAFT_PORT:-7878}` (java.net.http, no Origin header), hello on
every connect, reconnect forever with backoff 0.25 s doubling to 5 s, 15 s pings, a watchdog drops
a silent connection after 45 s. `AGENTCRAFT_FOREMAN=0` disables it.

## Anchors (`dev.agentcraft.layout`)

The single source of named world positions. An HQ builder publishes a layout; it is saved as
`agentcraft-anchors.json` in the world folder and reloaded on start. Read it anywhere:
`Anchors.current()` (immutable `Layout`: name, revision, bounds, anchors), `Anchors.get("desk_kit")`,
`Anchors.addListener(layout -> ...)`.

`Anchor(name, x, y, z, yaw, pitch)`. Stations/agent spots: **feet** position + facing yaw. `cam_*`:
**eye** position + view. Block anchors (`monitor_*`, `task_wall`, `decision_podium`): surface centre,
yaw = the direction the front faces. Yaw: 0 = +Z (south), 90 = -X (west), 180 = north, -90 = east.

Naming contract (`AnchorNames`, required from every HQ builder):

| name | meaning |
|---|---|
| `desk_<agentId>`, `monitor_<agentId>` | per cast agent (marlow juniper kit wren rowan tove) |
| `library`, `terminal`, `testbench`, `mergestation`, `meeting`, `lounge`, `user` | shared station slot 1 (`user` = next to the player / podium) |
| `<station>_2` .. `_N` | more slots at the same station (lounge needs 6: off-shift agents go there) |

**Slot spacing:** nameplates declutter themselves (see Agents), so tight slots never garble, but
plates sit at their natural height only when neighbours are far enough apart on screen. A full plate
is up to 3.15 blocks wide, a name-only pill about 0.9-1.5. Space shared-station slots **at least 1.6
blocks** apart (2+ where agents show activity, e.g. library, meeting), and stagger rows so the back
row is not directly behind the front row. The test room's lounge (1.4 apart, two rows) is the
deliberate worst case.
| `task_wall`, `decision_podium`, `goal_atrium`, `entrance`, `spawn` | fixed points |
| `cam_<name>` | QA camera points |

QA: `dev.anchors {prefix?}` lists them; `dev.camera {anchor:"cam_agents"}` puts the camera on one
(fields given explicitly still win; non-cam anchors stand you on the spot). The temporary test room
(`/agentcraft hq`, `hq.TestRoomBuilder`) writes all of the above plus `cam_overview`, `cam_room`,
`cam_agents` (3 agent faces in the showcase), `cam_commons`, `cam_desk_<id>`, `cam_desks_back`,
`cam_library`, `cam_task_wall`, `cam_east_wall`, `cam_podium`, `cam_blockrow(_left/_right)`.

**HQ specialist:** implement `hq.HqBuilder` (deterministic, idempotent, writes the anchors, sets
`bounds` to the walkable region, binds station block entities), register it in
`HqFeature.init()` with `HqBuilders.register(...)` and `HqBuilders.setDefault(id)`. `/agentcraft hq`
then builds yours; `/agentcraft hq test` still builds the test room.

## The real HQ (`hq.StudioHqBuilder`, default builder `studio`)

`/agentcraft hq` builds it (`/agentcraft hq test` still builds the Phase 2 test room); a fresh HQ
world builds it automatically on first start (`AGENTCRAFT_HQ_AUTOBUILD=0` turns that off). A long
timber-framed studio hall (x -24..24, z -10..6, floor blocks y=65, **agents stand at y=66**) with a
copper-domed octagonal Goal Atrium in front (centre 0,15; entrance and spawn at z=22, portico and
path to the south), terraces, pond, cottage garden and trees.

| zone | where | stations / anchors |
|---|---|---|
| Goal Atrium | octagon, centre (0, 66, 15) | `goal_atrium`; hologram = the `goal:atrium` status lamp's BER (goal text, progress ring, task/agent counts) |
| Task Wall | atrium west wall, 7 x 4 `task_board` facing east, x=-8, z 12..18, y 67..70 | `task_wall` (surface centre) |
| Decision Podium | atrium east side, podium at (6, 66, 15) facing west; alcove behind with a bell and the `decisions` lamp | `decision_podium`, `podium_user` (player's spot), `user`, `user_2`, `user_3` (waiting agents, facing the player) |
| Desks | north wall, bays at x -15 -11 -7 7 11 15 (cast order), 3 x 2 monitor at z=-9, lamp `agent:<id>` set into the wall above | `desk_<id>` (stand), `seat_<id>` (chair seat, for a future SIT pose), `monitor_<id>`, `cam_desk_<id>` |
| Library | west end | `library` .. `library_4`; memory archives bound `shared`, catalogs, lecterns |
| Test bench | east wall z -6..-2, CI lamps `ci:#1..#3` in the gable wall | `testbench` .. `testbench_3` |
| Terminals | north-east corner | `terminal`, `terminal_2` |
| Merge station | east wall z 1..2 | `mergestation`, `mergestation_2` |
| Lounge | fireplace, sofa (south-west) | `lounge` .. `lounge_6` (two rows facing each other, >= 1.6 apart) |
| Meeting | table x 8..11, z 0 | `meeting` .. `meeting_6` |

Cameras: every QA anchor (`cam_exterior_hero`, `cam_entrance_atrium`, `cam_task_wall`,
`cam_agent_desk` = Juniper's desk, `cam_wide_interior`, `cam_decision_podium`, `cam_night`,
`cam_console`, `cam_merge_station`, `cam_library`, `cam_desk_<id>`) plus `cam_hall`, `cam_lounge`,
`cam_testbench`.

Status lamp bindings (driven by `client.hq.HqWorldDriver`, applied on the integrated server only
when a state differs, re-applied every 2 s so rebuilt or newly placed lamps catch up; nothing
changes while the Foreman link is down): `agent:<id>` (state family; off when off shift or gone),
`ci:<repoId>` or `ci:#<n>` (n-th repo in Foreman order; unused slots idle), `goal` and
`goal:atrium` (planning thinking, active working, done, failed error), `decisions` (waiting while
any decision is open). Also driven: podium `open` (any open decision), merge station `active` (an
open merge decision), monitor `lit` (its agent is on shift). Waiting lamps breathe (BER glow) and
shed clay motes; an open podium sheds motes too.

Build contract: everything inside the site box x -46..46, y 60..100, z -36..54 is rebuilt from an
in-memory plan and applied as a diff (only differing cells are written, no neighbour updates;
connections of stairs/panes/fences/panels computed in a second pass; Foreman-driven properties
kept). Nothing outside the box is touched. A rebuild of an unchanged world writes 0 cells (about
20 ms); a fresh world about 0.5 s including chunk generation.

QA hooks: `dev.state.hq` (layout, wanted lamp states, podium/merge flags, cells changed by the last
apply) and `dev.hq.check {minLight?}`: the agents' own A* from `entrance` and `lounge` to every
standing anchor, spots that would stand on furniture, shared slots closer than 1.6, and block light
over every roofed walkable cell (last run: 66 routes, 0 unreachable, light min 9 / mean 11.1).

## Blocks and block entities (`dev.agentcraft.block`)

All 16 blocks from the assets-src block contract (README "Block contract") are registered with
their properties, facing rule (front faces the placer), luminance and shapes, with block items in
the "AgentCraft Studio" creative tab (`dev.screen {open:"creative_agentcraft"}`). Render layers are
automatic in 26.x (each quad picks solid/cutout/translucent from its sprite).

Stations with dynamic content have a block entity (`block.entity.*BlockEntity`, all extend
`StationBlockEntity`): monitor, task_board, decision_podium, merge_station, status_lamp,
console_terminal, memory_archive. Each carries a **binding** string (saved + synced) that the HQ
builder sets: monitor = agent id, status_lamp = `agent:<id>` / `ci:<repoId>` / `goal`, memory_archive
= scope, others empty = "the default". Add your own fields to your station's BE class and call
`changed()` after changing them.

Connectable panels (`PanelBlock`: monitor, task_board): `up/down/left/right` connect same-facing
neighbours; `PanelBlock.origin(...)` / `extent(...)` give the bottom-left block and the size of the
whole surface. The BER base draws from the origin only.

**BER base** `client.world.StationRenderer<T, S extends StationRenderState>`: extract fills
`binding`, `facing`, `panelOrigin`, `panelWidth/Height`, `timeSeconds`, `foremanRevision`; override
`extractStation` (read the Foreman model there, never in submit) and `submit`.
A surface drawn only from its origin block disappears when that block is culled (off screen, or
behind the camera while the rest of the panel is in view): override
`shouldRenderOffScreen()` to return true (and `getViewDistance()` if needed) in renderers of
multi-block panels.
`StationRenderer.toFace(poseStack, facing, depth, pxPerBlock)` gives screen-pixel space on the block
front (x right, y down, origin top-left as seen by a viewer); depth of the north-facing model: monitor
screen `12/16`, task board linen `14/16` (minus a hair). `MonitorRenderer` uses it for its Phase 2
placeholder (agent name on the screen), which proves the transform.

World state driven by the Foreman (lamp `status`, podium `open`, merge station `active`, monitor
`lit`): change blocks on the integrated server with `client.world.ServerTasks.run(level -> ...)`
(singleplayer; only set a state when it differs). Clicks on stations: 
`StationInteractions.onUse(ModBlocks.CONSOLE_TERMINAL, (player, pos, state, be) -> open...)`
(client side, consumed, sneak-click still places blocks).

## Agents (`client.agents`)

Architecture A (decided by measurement, see DEV.md "Agents: client-side"): agents are client-only
`ClientAgentEntity` (extends `Avatar`, drawn by the vanilla player model with both skin layers and
slim/wide arms from cast.json). `AgentManager` keeps one per Foreman agent in sync every client tick:
spawn at the target anchor, walk on station changes (`GridPathfinder` A* over the real blocks +
string pulling, `AgentMotion` 2.9 blocks/s, smooth turns, exact arrival + facing), teleport when
there is no route, remove agents that leave the snapshot, keep them dimmed ("Foreman offline") while
the link is down, re-place everyone when the layout is republished. `StationAssigner` maps station ->
anchor slot (sticky, deterministic; off-shift -> lounge).

What an agent shows lives in its `AgentView` (`entity.view()`: name, colours, state, family,
activity, station, anchor, active, paused, stale, pose). Phase 3 hooks:

```java
AgentHooks.onTick(agent -> ...);                               // per tick, after movement: particles, look-at, idle anims
AgentHooks.onExtract((agent, state, pt) -> ...);               // adjust AgentRenderState (pose, head rotation, extra fields)
AgentHooks.onSubmit((state, poseStack, collector, camera) -> ...); // extra geometry at the entity origin (speech bubbles)
AgentsFeature.onClick((player, agent) -> ...);                  // right-click an agent (never sent to the server)
```

Poses: `AgentPose.SIT` maps to the vanilla riding pose, `LEAN` to crouch (stubs; Phase 3 adds seats
and offsets).

**Nameplates** (`Nameplate` + `PlateLayout`): an opaque kit pill with status dot + name (agent
colour) + activity, visible within 40 blocks (also with the HUD hidden), never overlapping another
plate on screen once settled:
- Two cached variants per agent: full, and a name-only pill. Low-value plates
  (`AgentView.plateWeight()` <= 2: idle, done, off shift, Foreman offline) collapse to the pill when
  their full plate would touch another plate; the plate under the crosshair always shows in full.
- `PlateLayout` (once per frame, Fabric `LevelExtractionEvents.END_EXTRACTION`) places plates in
  priority order (focused, waiting, error, working/thinking, then the low-value tier; nearest first
  within a tier) at the lowest lift above the head that is free, slides lifts smoothly, draws a
  leader line from a lifted plate to its head, and gives each plate a depth nudge by rank so that two
  plates that still overlap (mid-slide) never show each other's text. It writes `plate`,
  `plateLift`, `plateScale`, `plateNudge` into `AgentRenderState`.
- Plates keep their world size up to 12 blocks, then grow to 1.6x (mid-distance legibility).
- Anything that belongs with the plate (speech bubbles, a "!" above a waiting agent) should draw in
  plate space from an `AgentHooks.onSubmit` hook, above `Nameplate.top(state)`:
  `poseStack.pushPose(); Nameplate.plateSpace(state, poseStack, camera); ...; poseStack.popPose();`.
  A bubble taller than the plate should either be part of the plate's layout (extend `PlateLayout`
  so it reserves the space) or appear only for the focused/nearest agent, or it will cover plates
  stacked above.
- Draw such billboards opaque (`WorldUi.Layer.SOLID`); translucent billboards let other plates'
  text ghost through (DEV.md "Phase 2 gotchas").

`dev.agents {settle?}` lists positions/targets/paths and each laid-out plate (`plate{mode, lift,
rank, rect, ...}`); `settle:true` snaps walkers to their targets and plates to their final layout
before a shot. `dev.state.agents.plateOverlaps` counts overlapping plates in the last frame (0 when
settled).

## UI kit (`client.ui`)

```java
UiStyle.color("paper.text"); UiStyle.color("monitor.diff_add"); UiStyle.status("waiting");
UiStyle.agentOnLight("kit"); UiStyle.agentOnDark("kit"); UiStyle.metric("metrics.gui_panel_padding", 8);
Kit.PANEL_PAPER, Kit.FRAME_BRASS, Kit.button(primary, "hover"), Kit.dot("working", halo), Kit.card("doing"),
Kit.progressRing(p), Kit.icon("edit"), Kit.padding("panel_paper")   // content padding from kit.json
Panels.panel(g, x, y, w, h); Panels.header(g, font, "Title", x, y, w); Panels.button(g, font, "Merge", x, y, w, true, hovered, false);
Panels.progress(g, x, y, w, 0.4, "teal"); Panels.pill(g, font, "@kit", x, y, color); Panels.scrollbar(g, x, y, h, scroll, hovered);
TextUtil.ellipsize(font, s, px); TextUtil.wrap(font, s, px); new TextUtil.Scroll().update(rows, viewRows).scrollBy(n);
WorldUi.billboard(poseStack, camera, x, y, z);                       // in-world pixel space facing the camera
WorldUi.billboard(poseStack, camera, x, y, z, scale, nudge, ox, oy, oz); // + size factor + depth nudge (see javadoc)
WorldUi.submitNineSlice(ps, c, WorldUi.Layer.SOLID, Kit.BUBBLE, ...);  // opaque plate: use for billboards
WorldUi.submitNineSlice(...); WorldUi.submitSprite(...); WorldUi.submitText(...); WorldUi.submitFill(...);
```

World UI layers: `SOLID` (opaque, depth-correct, overlap-safe: billboards, bubbles, labels),
`BASE` (translucent, the sprite's own alpha: only on block faces nothing else overlaps), `OVERLAY`
(icons/dots on top of a plate). Opaque text renders in the solid pass, so a translucent plate can
never hide text behind it.

Screens: paper panels, ink text, no shadows, no vanilla grey GUI (docs/visual-bar.md). Never
hard-code colours; ask `UiStyle`. Sprites are 1 texel = 1 GUI px (GUI scale 3 at 1080p).

## HUD (`client.hud`)

`HudElementRegistry.addLast(AgentCraft.id("hud/<name>"), element)`. Phase 2 ships the connection
banner (top right: "Foreman · sim" / "Reconnecting to the Foreman" / "Foreman not running"; loud
paper banner at the top centre when claude auth failed). Phase 3: goal boss bar, toasts for `notify`.

## Making things shootable (QA)

- Screens: `DevBridge.registerScreen("console", mc -> new ConsoleScreen())` in your `init()`, then
  `dev.screen {open:"console"}` (or a scene shot with `"screen":"console"`). Names in use or reserved:
  `console`, `diff`, `decision`, `library`, `permission`, `creative_agentcraft`.
- Cameras: add `cam_<name>` anchors in the HQ builder and shoot with `dev.camera {anchor}`.
- State: `dev.state` has `foreman` (link, backend, auth, counts, goal) and `agents` (count, moving);
  `dev.foreman` (+ `reconnect:true`), `dev.foreman.send {message:{type,...}}` (drive the Foreman
  through the mod's own link, e.g. submit a goal or answer `d3`), `dev.agents {settle}`,
  `dev.anchors`. With `AGENTCRAFT_DEV_TEST=1`: `dev.test.foremanMessage {message}` applies a message
  as if the Foreman sent it (e.g. `foreman.status` with `auth:"failed"`).
- Foreman for QA (always a project-local home, your own port and **your own profile name**, since
  profiles are locked per running Foreman):
  `npm run start -- --backend sim --profile <you>-showcase --reset --showcase --home C:\Projects\agentcraft\.agentcraft-home --port <p> --no-notify`
  then launch the game with `AGENTCRAFT_PORT=<p>`. The sim's repo id is `sim-demo-<profile>` cut to
  24 characters (`--profile verify2-core-showcase` gives `sim-demo-verify2-core-sh`), so read it from
  the decision (`decision.repoId()`) or `s.repos()` instead of building it (decision `d3` = merge of
  `wren-t4`). The live sim (`--reset --speed 4`, no `--showcase`) starts
  on the first `goal.submit`.
