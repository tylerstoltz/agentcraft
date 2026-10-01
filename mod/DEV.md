# AgentCraft mod: developer notes

Fabric mod `agentcraft` (package `dev.agentcraft`). Common entrypoint `dev.agentcraft.AgentCraft`,
client entrypoint `dev.agentcraft.client.AgentCraftClient`. Loom's split source sets are used:
`src/main` (both sides) and `src/client` (client only).

## Versions, and why

| Thing | Version |
|---|---|
| Minecraft | **26.3** (stable, released 2026-09-15) |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.161.0+26.3 |
| Loom | 1.18.2 (`net.fabricmc.fabric-loom`, the non-remapping plugin), pinned to a release instead of the template's `1.18-SNAPSHOT` |
| Gradle | 9.7.1 (wrapper from the official template) |
| Java | 25 (runtime and `--release 25`) |
| DevBridge WebSocket | org.java-websocket:Java-WebSocket 1.6.0 (nested with jar-in-jar; slf4j comes from Minecraft) |

The spec said 1.21.x. **26.3 was chosen instead** for these reasons:
- It is the newest stable release, with an actively maintained Fabric API, and it is the default branch
  of the official `FabricMC/fabric-example-mod` template.
- From 26.1 on, Minecraft is **unobfuscated**. There is no mapping layer and no remapping (so builds
  are faster), and the decompiled sources use Mojang's real names. For agents this is the biggest win:
  every API can be checked by grepping the real sources (see "Finding Minecraft APIs").
- 26.x requires Java 25, which is the JDK installed here. 1.21.11 would have targeted Java 21.

The cost is that many APIs changed after 1.21. Do **not** code from 1.21-era memory. Check the
26.3 sources. Changes that matter to this project:
- **Windowing and input use SDL3, not GLFW** (`com.mojang.blaze3d.platform.Window`, `SDLHints`).
  Key codes are SDL scancodes (`InputConstants.KEY_ESCAPE == 41`).
- Rendering is split into extract and render passes (`GameRenderer.extract/render`, `*RenderState`).
  Screens are drawn through `GuiGraphicsExtractor`. Backends live under `com.mojang.renderpearl`.
  OpenGL is the default; Vulkan is optional (options key `preferredGraphicsBackend`).
- Screens: `mc.gui.setScreen(..)` and `mc.gui.screen()` (no longer on `Minecraft`). The HUD is `mc.gui.hud`
  (`isHidden()` and `toggle()` replace `options.hideGui`).
- Game rules are snake_case registry entries (`GameRules.ADVANCE_TIME`, `KEEP_INVENTORY`, ...).
- Day time comes from world clocks: `level.getDefaultClockTime()`. `/time set` still works.
- `Identifier` replaces `ResourceLocation`. Permissions use `PermissionSet` and `LevelBasedPermissionSet.OWNER`.
- **Resource pack format is 97.1, data pack format is 121** (from the jar's `version.json`). The art
  track should target these.

## Build and run

Always isolate Gradle:

```bash
cd mod
GRADLE_USER_HOME=C:/Projects/agentcraft/.gradle-home ./gradlew build        # jar -> mod/build/libs/agentcraft-0.1.0.jar
GRADLE_USER_HOME=C:/Projects/agentcraft/.gradle-home ./gradlew runClient    # dev client
GRADLE_USER_HOME=C:/Projects/agentcraft/.gradle-home ./gradlew --stop       # stop OUR daemons only
```
(PowerShell: `$env:GRADLE_USER_HOME='C:\Projects\agentcraft\.gradle-home'; .\gradlew.bat runClient`.)

`runClient` starts with **no clicks**:
1. `prepareRunDir` copies `run-template/options.txt` to `mod/run/options.txt`, but only if that file
   does not exist yet. The template sets: master and music volume 0, `pauseOnLostFocus:false`,
   `guiScale:3` (1080p), render distance 16, chunk fade-in off (so screenshots are never half-faded),
   no tutorial, accessibility onboarding or narrator, no Realms notifications, and the
   inactivity FPS limit set to "minimized" only. Delete `mod/run/options.txt` to reset it.
2. Program args: `--username Blendi --width 1920 --height 1080`.
3. **AutoWorld** (client) runs the first time the title screen appears. It loads the world folder
   `AgentCraft HQ` if it exists, and otherwise creates it: creative, peaceful, commands allowed, no
   structures, no bonus chest, superflat plains meadow (bedrock / 124 stone / 3 dirt / grass), so
   **the grass top is y=64 and you stand at y=65**. World spawn is 0 65 0.
4. **HqWorld** (server side, HQ world only) re-applies the game rules on every start: no time or
   weather cycle, keep inventory, no fire spread, no mob spawning of any kind, no mob griefing, no vine
   spread, no snow build-up, respawn radius 0, no locator bar, quiet advancement and command output,
   and max_block_modifications 1,000,000 so large /fill commands work for the HQ builder. On first
   creation it also sets time 12000 (golden hour), clear weather and the world spawn. It writes a
   marker file `agentcraft-world.json` in the world folder. Players who join in spectator or survival
   are put back into creative.

Delete `mod/run/saves/AgentCraft HQ` to start over with a fresh world.

### Environment switches (env var, or `-Dagentcraft.xxx=` system property)

| Var | Default | Effect |
|---|---|---|
| `AGENTCRAFT_DEV_PORT` | 7879 | DevBridge port (always bound to 127.0.0.1) |
| `AGENTCRAFT_DEV` | 1 | `0` disables the DevBridge |
| `AGENTCRAFT_MUTE` | 1 | Forces master and music volume to 0 at startup. **Set `0` for real use** (for example in launch.ps1) to keep your own volume |
| `AGENTCRAFT_FOCUS` | 0 | `0`: the window is shown **without activating it**, so it never steals focus. `1`: normal "come to front" |
| `AGENTCRAFT_AUTOWORLD` | 1 | `0`: stay on the title screen |
| `AGENTCRAFT_SHOTS_DIR` | `<repo>/artifacts/shots` | Where `dev.screenshot` writes |
| `AGENTCRAFT_DEV_ALLOW_ORIGIN` | 0 | `1` lets browser pages (which send an Origin header) connect. They are refused by default |
| `AGENTCRAFT_DEV_TEST` | 0 | `1` registers test-only commands (`dev.test.stall`, which blocks the render thread to simulate a hung game). Never set it for real use |

The defaults (muted, no focus) suit unattended agent runs. `tools/launch.ps1` should set
`AGENTCRAFT_MUTE=0 AGENTCRAFT_FOCUS=1` when Blendi launches the game himself.

### Focus behaviour (what was verified)
`RenderSystemMixin` sets the SDL hints `SDL_WINDOW_ACTIVATE_WHEN_SHOWN=0`,
`SDL_WINDOW_ACTIVATE_WHEN_RAISED=0` and `SDL_FORCE_RAISEWINDOW=0` before SDL starts. SDL3 then shows
the window with `SWP_NOACTIVATE`. Verified on Windows 11: after launch, `GetForegroundWindow()` was
still the previously active app. `dev.state` reports `window.osForeground:false`, read through a
read-only user32 call using Java FFM.

Caveat: **SDL's own flag (`window.focused`) still says `true`.** The window can still appear on top
of other windows; it just isn't activated. Window *placement* is untouched.

## DevBridge (dev control WebSocket)

A WebSocket server **inside the client**, listening on `ws://127.0.0.1:${AGENTCRAFT_DEV_PORT:-7879}`. It
starts at `CLIENT_STARTED`, before the world loads, so `dev.ping` works during loading. It stops at
`CLIENT_STOPPING` and uses daemon threads. If the port is busy it logs an error and the game keeps running.

**Protocol.** Each frame carries one JSON object (text frames; binary frames holding UTF-8 JSON are
treated the same, other binary frames get an `ok:false` reply).
- Request: `{"id":"7","type":"dev.camera", ...fields}`. `id`, `type` and `timeoutMs` are reserved
  names, so never use them as payload field names.
  - `id` (optional) is a string or a number and is echoed back **exactly as sent** (`7` stays a number).
    Any other id (object, array, bool) gets an `ok:false` reply that still carries the id.
  - `type` must be a JSON string (`["dev.state"]` or `5` are refused).
  - `timeoutMs` (optional, integer 1..3600000) overrides the command's server-side timeout.
- Reply: `{"id":"7","type":"dev.camera","ok":true, ...result}` or `{"id":"7","type":"dev.camera","ok":false,"error":"..."}`.
  Replies always include null-valued fields (for example `dev.state` `screen: null`, `foreman: null`).
- **Fields are strictly typed** and errors name the field: numbers must be JSON numbers and finite (the
  strings `"NaN"`/`"12"`, bare `NaN`/`Infinity` and overflowing `1e400` are refused), integers must be whole,
  booleans must be `true`/`false` (the string `"false"` is refused), and every number has a range. Example:
  `field 'ticks' must be an integer (got string "noon")`, `field 'lookAt.x' must be a finite number (got string "NaN")`.
- A timeout while the render thread is stuck carries `stalled:true` and says so in `error`.
- On connect the server sends `{"type":"dev.hello","protocol":1,"minecraft":"26.3","inWorld":bool}`.
- Requests may be in flight at the same time; match replies by `id`. All game work is done on the
  render thread (`mc.execute` or the end-of-frame scheduler) or on the integrated server thread
  (`server.submit`), and the reply is sent asynchronously. Every handler error becomes an
  `ok:false` reply; nothing is thrown into the game. Unexpected exceptions are reported as
  `internal error: <exception>` and logged with a stack.
- Connections that send an `Origin` header (browsers) are refused with HTTP 404.

| Command | Fields | Result / notes |
|---|---|---|
| `dev.ping` | (none) | `{pong, frame, msSinceLastFrame, stalled, quitting}`. Answered on the socket thread, so it answers while the game loads **and while it is hung**. `stalled:true` = the render thread has not finished a frame for 5 s (QA: relaunch) |
| `dev.help` | (none) | All commands with help text, plus registered screens |
| `dev.state` | (none) | `inWorld`, **`ready`** (in a world with no loading screen or overlay: safe to shoot), `paused` (a pausing screen is open, so the integrated server is stopped), `screen{class,title}` or null, `fps`, `frame`, `window{width,height,framebufferWidth/Height,renderWidth/Height,guiScale,focused,osForeground,iconified}`, `hudHidden`, `fov` (what the last frame was rendered with), `fovOption` (the player's setting), `fovPin` (dev camera pin, null = none), `cameraType`, `audio{master,music}`, `player{name,x,y,z,eyeY,yaw,pitch,flying,gameMode}`, `camera{x,y,z,yaw,pitch,fov}`, `world{name,dimension,time,raining,thundering}`, `chunks{renderedAll,lightQueue,loadedAll,renderDistance}` (player/camera/world/chunks are null outside a world), `foreman` (null until the integration phase adds a contributor) |
| `dev.camera` | `x,y,z` + (`yaw,pitch` **or** `lookAt:{x,y,z}`, lookAt wins), `fov?` (30-110, may be fractional; **default: the player's FOV option**), `mode?` = `spectator` (default) / `creative` (flying) / `keep`, `feet?` (default false: x,y,z is the **eye** position), `hideHud?`, `closePause?` (default true: closes a vanilla pause menu first) | Validates first: all numbers finite; `pitch` in [-90, 90]; `yaw` any finite value (wrapped to [-180, 180)); `y` in [-20000000, 19999999] (vanilla `/tp`'s limit); x/z inside the **world border** (±29999984); lookAt not equal to the eye. Then forces first person, stops spectating other entities, teleports on the server thread, waits until the client has the exact position, pins position and rotation with no interpolation, and **only replies ok once a rendered frame used exactly the requested eye position (±0.01), rotation (±0.05°) and FOV**; otherwise `ok:false` with wanted vs got (`mode:keep` skips that check, since walking players fall). Returns the actual `camera{x,y,z,yaw,pitch,fov}`. Yaw: 0 = +Z (south), 90 = -X (west), -90 = +X (east). Pitch: positive looks down. **FOV pin:** each call renders with exactly its `fov` (or the option), ignoring vanilla's dynamic FOV (flying widens it by 1.1x, so before this pin a "70" shot really rendered at 77). Nothing carries over between calls and `options.txt` is never touched |
| `dev.release` | `mode?` = `creative` (default) / `keep` | Hands the view back to the player: clears the FOV pin (vanilla FOV again), shows the HUD, spectator -> creative (flying, so you don't fall) |
| `dev.screenshot` | `name` (letters, digits, `_ - . /`; `.png` added), `hideHud?` (default true), `frames?` (3, 1-600), `waitChunks?` (true), `chunkRadius?` (whole render distance, 0-64), `chunkTimeoutMs?` (30000, 0-600000) | Hides the HUD, waits until all chunks within the render distance are loaded and meshed and the light queue is empty for 5 consecutive frames, waits N more frames, then copies the **main render target** (the framebuffer, so it doesn't depend on window focus or overlap). Writes the PNG and restores the HUD. Returns `{path, width, height, ms, chunksTimedOut, paused, stats{meanLuma,stdLuma,darkFraction}}`. Use the stats to catch black frames. Open screens are included in the capture. `width`/`height` are **not supported** (the shot is the window framebuffer size, 1920x1080). The default request timeout grows with `chunkTimeoutMs` and `frames` |
| `dev.time` | `ticks` (integer 0..2147483647) | `/time set`. 6000 noon, 12000 golden hour (the HQ default), 18000 night, 23300 sunrise |
| `dev.weather` | `weather: clear/rain/thunder` or `clear: bool` | Rain fades in over several seconds (vanilla behaviour) |
| `dev.command` | `cmd` (non-empty string, leading `/` optional) | Runs as the player with owner permissions on the integrated server. Returns `{cmd, messages[], success, result}` (`result` null when the command did not parse). Feedback is captured, not shown in chat. An idempotent `/fill` reports `success:false` with "No blocks were filled" |
| `dev.screen` | `open: name` or `null` (absent = null) | Built in: `title`, `pause`, `chat`, `inventory`, `options`, plus any registered screens. `null` closes the screen (on the title screen it stays on title). Returns the resulting `screen` class or null |
| `dev.key` | `key` (`escape`, `key.keyboard.f3`, ...) + `modifiers?` (0-65535), or `mapping` (`key.chat`) | Sent to the open screen's `keyPressed`, otherwise as a key-mapping click |
| `dev.type` | `text` (up to 100000 chars) | `charTyped` into the open screen's focused widget |
| `dev.hud` | `hidden: bool` | Like F1 |
| `dev.waitChunks` | `timeoutMs?` (30000; also the request timeout), `radius?` (0-64) | Waits for chunks to load and build (same condition as screenshots) |
| `dev.wait` | `frames?` (0-36000), `ms?` (0-600000) | Waits for rendered frames and/or wall time; the default request timeout grows with both |
| `dev.quit` | `forceAfterMs?` (15000, 1000-600000) | Replies `{quitting, alreadyQuitting, renderThreadStalled, forceAfterMs}`, then calls `mc.stop()` 250 ms later: the world saves and the JVM exits (about 1-2 s). **Watchdog:** if the render thread is hung and has not run the stop after `forceAfterMs`, it logs the render thread's stack, stops the integrated server (which saves the world), then halts the JVM with **exit code 3** (`runClient` then ends with BUILD FAILED). If a normal shutdown is still running after 90 s it does the same with exit code 4 |
| `dev.test.stall` | `ms` (1-600000) | **Test only** (`AGENTCRAFT_DEV_TEST=1`): blocks the render thread to simulate a hang |

### Extending it from other mod code (client side)

```java
DevBridge.register("dev.foreman", 10_000, "{} -> foreman link status", (req, mc) -> {
    Fields f = Fields.of(req);                       // strict typed fields, errors name the field
    int limit = f.optInt("limit", 20, 1, 500);
    return DevBridge.onClient(mc, () -> { JsonObject o = new JsonObject(); /* ... */ return o; });
});
DevBridge.register("dev.slow", req -> Fields.of(req).optLong("ms", 0, 0, 60_000) + 10_000, "...", handler); // request-dependent timeout
DevBridge.registerScreen("console", mc -> new ConsoleScreen());   // dev.screen {open:"console"}
DevBridge.addStateContributor((mc, state) -> state.add("foreman", foremanStatusJson()));
FrameScheduler.afterFrames(2).thenRun(...);                        // end-of-frame callbacks (render thread)
FrameScheduler.when(() -> condition, minFrames, stableFrames, timeoutMs, "what");
FrameScheduler.msSinceLastFrame(); FrameScheduler.stalled();      // render-thread liveness, any thread
```
Register in `onInitializeClient`. Built-ins are registered when the bridge starts, so a later
`register` with the same name replaces a built-in. Throw `DevBridge.DevException` for user errors
(sent back verbatim); any other exception becomes `internal error: ...` and is logged.
`Fields` (`num`, `num(min,max)`, `optNum`, `integer`, `optInt`, `optLong`, `bool`, `optBool`, `str`,
`nonBlank`, `optStr`, `obj`, `optObj`) refuses NaN/Infinity, wrong JSON types and out-of-range values.

## Tools (repo `tools/`, Node 22, local `ws` dependency: run `npm install` in tools/ once)

```bash
node tools/devcli.mjs wait                         # wait for the bridge + a ready world (300 s)
node tools/devcli.mjs state
node tools/devcli.mjs camera -4 72 -8 -37.4 13.1 70        # x y z yaw pitch [fov]
node tools/devcli.mjs camera 20 66.5 20 80 --look 9,67,9   # aim at a point
node tools/devcli.mjs shot my_shot [--hud] [--frames 5] [--no-wait-chunks]
node tools/devcli.mjs cmd "/fill 0 65 0 4 69 4 minecraft:oak_planks"
node tools/devcli.mjs time 12000 | weather clear | hud off | screen pause | key escape | type "hi"
node tools/devcli.mjs raw '{"type":"dev.waitChunks","timeoutMs":60000}'
node tools/devcli.mjs quit                         # saves; waits for the bridge to close
node tools/shoot.mjs tools/scenes/phase1.json [--only a,b] [--manifest out.json] [--verbose]
```
`devcli` prints the JSON reply and exits 1 on `ok:false`. `--port` overrides the port. `--timeout`
sets the connect timeout in seconds. `devcli release` hands the view back after dev camera use.
`tools/lib/devclient.mjs` exports `DevClient` (`connect`, `request`, `call`, `waitInWorld`,
`waitClosed`, `health`, `assertNotHung`) for other scripts. `health()` uses dev.ping and works on a
hung game; `waitInWorld` (so `devcli wait` and `shoot.mjs`) fails fast with "game is hung" once the
render thread has been stuck for 30 s, and `shoot.mjs` stops at the first failed shot if the game
looks hung (`hung` in its JSON summary) instead of timing out on every remaining shot. Relaunch by
`devcli quit` (force-exits a hung game after 15 s) and `gradlew runClient`.

Scene format for shoot.mjs (see `tools/scenes/phase1.json`): `{defaults, setup:[cmds], shots:[{name,
camera:{x,y,z,yaw,pitch | lookAt, fov}, time?, weather?, commands?, screen?, keepScreen?, hideHud?,
frames?, delayMs?, waitChunks?}]}`, or a bare array of shots. Shots are written to `artifacts/shots/<name>.png`.
A shot without `fov` renders at the player's FOV option (70), exactly; scenes no longer inherit the
previous shot's FOV.

## Finding Minecraft APIs

```bash
GRADLE_USER_HOME=C:/Projects/agentcraft/.gradle-home ./gradlew mcSources   # genSources + unpack into mod/build/mcsrc
grep -rn "class LevelRenderer" mod/build/mcsrc/net/minecraft
```
Fabric API module sources are in the official maven, for example
`https://maven.fabricmc.net/net/fabricmc/fabric-api/<module>/<version>/<module>-<version>-sources.jar`.
The module versions are listed under `.gradle-home/caches/modules-2/files-2.1/net.fabricmc.fabric-api/`.
`fabric-client-gametest-api-v1` is a good reference for world creation, screenshots and chunk waiting in 26.3.

## Timings (this machine, RTX 4090)

| Step | Time |
|---|---|
| Cold `gradlew build`: fresh copy of `mod/` (no `build/`, `.gradle/`), **empty `.gradle-home`** (the real one moved aside, then restored), `--no-daemon`; downloads Gradle 9.7.1, Loom, MC and Fabric API, about 580 MB (`artifacts/logs/fix-coldbuild.log`) | 61 s |
| `genSources` (first time) | 72 s |
| Incremental `gradlew build` (warm daemon) | 2-4 s |
| `runClient` to ready world, first ever run (Gradle configure + world creation) | 48 s |
| `runClient` to ready world, warm daemon, existing world | 14-19 s |
| `runClient` to ready world, warm daemon, fresh run dir and new world | 17 s |
| `dev.quit` to JVM exit | 1-2 s |
| `dev.quit` on a hung render thread (`forceAfterMs` 5000) to JVM exit, world saved | 5.9 s |
| Screenshot (chunks already loaded) | about 0.4-0.8 s |
| Screenshot after a 6000-block teleport (waits for 16-chunk radius) | about 5 s |
| Soak: 90 camera moves and shots (18 of them far teleports) in 3 min | 0 failures, 59-60 fps (vsync) |
| Soak after the fix round: 42 verified camera moves + shots (6 far teleports, mixed modes/FOVs) | 68 s, 0 failures, 59-60 fps |

## Known issues

- **One JVM crash in about 8 sessions** (Temurin 25.0.1): `EXCEPTION_ACCESS_VIOLATION` in the *C2 JIT compiler
  thread* while compiling `LevelExtractor::extract`, about 34 s after launch (`artifacts/logs/hs_err_pid104408.log`).
  No third-party DLLs were loaded and no mod code was on the stack. Repeating the exact same sequence
  and a 3-minute soak did not reproduce it. If it recurs, tools should treat "bridge gone" as "relaunch"
  (the world autosaves). Possible mitigations, both untested: a newer JDK 25 update, or
  `-XX:CompileCommand=exclude,net/minecraft/client/renderer/extract/LevelExtractor.extract`, which costs
  per-frame speed. A shot that is in progress when the JVM dies leaves a 0-byte PNG.
- **An unexplained pause menu** appeared once during an unattended run (`focused` flipped to false at the
  same moment, with `pauseOnLostFocus=false`). In 26.3 only a real Escape key event or focus-pause opens
  it. A pause menu stops the integrated server, so chunks stop loading. Mitigations now in place:
  `dev.state.paused`; `dev.camera` closes a vanilla pause menu (`closedPauseScreen:true`; disable with
  `closePause:false`); chunk waits fail with a clear "game is paused" error; and every pause-screen
  open is logged with a short stack ("Pause screen opening ...") so the source can be found next time.
- `width`/`height` for `dev.screenshot` are not implemented. Shots are always the window framebuffer size (1920x1080).
- Rain fades in over a few seconds after `dev.weather rain`.
- A forced `dev.quit` (exit code 3/4) skips the client's own shutdown (options, resource cleanup); the
  world itself is saved by the integrated server first. The exit-code-4 path (a normal shutdown that
  hangs) has not been observed or tested; the exit-code-3 path was tested with `dev.test.stall`.
- `dev.key` sends keycode 0 alongside the scancode, so shortcuts that depend on the keycode (such as
  Ctrl+C in text fields) may not work through it.

## Gotchas hit

- `type`, `id` and `timeoutMs` are reserved request fields. `dev.weather` first used `type` for the
  weather kind, which the message type silently overwrote. It is now `weather`.
- **A NaN camera hangs vanilla forever.** `Frustum.offsetToFullyIncludeCameraCube` loops until the
  camera cube is inside the frustum; with a NaN view vector that never happens, so the render thread
  spins and the game is dead (dev.quit could not even run). Gson's `getAsFloat()` happily parses the
  string `"NaN"`, and `pitch < -90 || pitch > 90` is false for NaN. All request fields now go through
  `Fields`, which refuses non-finite numbers. Huge-but-finite values are dangerous in the same loop
  (doubles lose the precision to move the camera), so y is limited like vanilla `/tp` and x/z to the
  world border.
- Gson's lenient getters are traps for a protocol: `getAsString()` on `["dev.state"]` returns
  `"dev.state"`, `getAsBoolean()` on `"yes"` returns false, `getAsLong()` on `1.5` truncates. Use `Fields`.
- Vanilla multiplies the FOV option by a dynamic modifier (1.1 while flying, which spectators always
  are). Shots requested at `fov:70` used to render at 77; `tools/scenes/phase1.json` was updated to 77/88
  to keep its framing, and the dev camera now pins the exact FOV (`CameraMixin` on `Camera.calculateFov`).
- Right after joining, `inWorld` is true while `LevelLoadingScreen` is still showing. Wait for `ready`.
- Waiting only for chunks near the camera left a square horizon after long teleports. Screenshots now
  wait for the whole render distance (circular, radius rd-1, matching the server's chunk tracking).
- `hasRenderedAllSections()` is true right after a teleport, before new sections are queued. The
  waiter therefore requires the condition to hold for 5 consecutive frames, after at least 6 frames.
- Chunk fade-in (new in 26.x, default 0.75 s) would make freshly loaded chunks look translucent in
  shots. It is set to 0 in the options template.
- Static `start()` and `stop()` names clash with `WebSocketServer`'s own methods, hence `startBridge` and `stopBridge`.
- Log noise that is harmless in dev: `Could not authorize you against Realms server` (offline dev
  account) and `Requested post effect does not exist: minecraft:end_of_frame`. The second is printed
  by vanilla 26.3 in dev, not by this mod.
- Don't set `setReuseAddr(true)` on the WebSocket server. On Windows that would allow two games to
  bind the same port.
- `mod/run/` is gitignored, and the server run dir is `mod/run/server`, so it is ignored too.
