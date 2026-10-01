#!/usr/bin/env node
// Scene screenshot runner: drives the DevBridge through a list of shots and writes PNGs to
// artifacts/shots/ (or AGENTCRAFT_SHOTS_DIR). The game must be running (gradlew runClient).
//
//   node tools/shoot.mjs tools/scenes/qa.json [--only a,b] [--port N] [--manifest out.json] [--verbose]
//
// Scene file: either an array of shots, or
// {
//   "defaults": { "time": 11000, "weather": "clear", "hideHud": true, "fov": 70, "mode": "spectator" },
//   "setup":    ["/fill ...", "/setblock ..."],          // commands run once before the shots
//   "shots": [
//     { "name": "phase1_a",                               // -> artifacts/shots/phase1_a.png
//       "camera": { "x": 0, "y": 70, "z": -12, "yaw": 0, "pitch": 15, "fov": 70 },  // eye position
//                                                           // fov omitted = the player's option (70), exact
//       "time": 6000, "weather": "clear",                 // optional per-shot overrides
//       "commands": ["/setblock 0 65 0 lantern"],         // optional, run before the camera move
//       "screen": "pause" | null,                         // optional: open a screen for this shot
//       "keepScreen": false,                              // close the screen afterwards (default)
//       "hideHud": true, "frames": 3, "delayMs": 0, "waitChunks": true }
//   ]
// }
// Prints a JSON summary; exit code 1 if any shot failed. Stops early (summary.hung) if the game hangs.

import fs from 'node:fs';
import path from 'node:path';
import { DevClient, DEFAULT_PORT } from './lib/devclient.mjs';

const argv = process.argv.slice(2);
let sceneFile = null;
let only = null;
let port = DEFAULT_PORT;
let manifest = null;
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === '--only') only = new Set(argv[++i].split(',').map((s) => s.trim()).filter(Boolean));
  else if (a === '--port') port = Number(argv[++i]);
  else if (a === '--manifest') manifest = argv[++i];
  else if (!a.startsWith('--') && !sceneFile) sceneFile = a;
}
if (!sceneFile) {
  console.error('usage: node tools/shoot.mjs <scene.json> [--only a,b] [--port N] [--manifest out.json]');
  process.exit(2);
}

const raw = JSON.parse(fs.readFileSync(sceneFile, 'utf8'));
const scene = Array.isArray(raw) ? { shots: raw } : raw;
const defaults = scene.defaults ?? {};
const shots = (scene.shots ?? []).filter((s) => !only || only.has(s.name));
const log = (m) => process.stderr.write(`[shoot] ${m}\n`);

let dev;
try {
  dev = await DevClient.connect({ port, timeoutMs: 120_000, onWait: (ms) => log(`waiting for DevBridge (${Math.round(ms / 1000)}s)...`) });
  await dev.waitInWorld({ timeoutMs: 300_000, onWait: (ms) => log(`waiting for world (${Math.round(ms / 1000)}s)...`) });
} catch (e) {
  // Bridge missing, world never ready, or the game is hung (e.hung): report as JSON like any other failure.
  dev?.close();
  log(`cannot start: ${e.message}`);
  console.log(JSON.stringify({ ok: false, scene: path.resolve(sceneFile), hung: e.hung ? e.message : null, error: e.message, shots: [] }, null, 2));
  process.exit(1);
}

async function run(type, payload) {
  const res = await dev.request(type, payload);
  if (!res.ok) {
    const err = new Error(`${type} ${JSON.stringify(payload)}: ${res.error}`);
    err.stalled = !!res.stalled;
    throw err;
  }
  return res;
}

const verbose = argv.includes('--verbose');
async function runCommands(cmds, label) {
  if (!cmds?.length) return;
  let failed = 0;
  for (const cmd of cmds) {
    const r = await run('dev.command', { cmd });
    // Re-running an idempotent /fill or /setblock reports "No blocks were filled": counted, not fatal.
    if (r.success === false) {
      failed++;
      if (verbose) log(`  ${cmd} -> ${r.messages?.join(' | ')}`);
    }
  }
  if (failed) log(`${label}: ${failed}/${cmds.length} commands reported failure or no change${verbose ? '' : ' (--verbose for details)'}`);
}

const results = [];
const started = Date.now();
let hung = null;
try {
  await runCommands(scene.setup, 'setup');
  let lastTime = null;
  let lastWeather = null;
  for (const shot of shots) {
    const t0 = Date.now();
    const s = { ...defaults, ...shot, camera: { ...(defaults.camera ?? {}), ...(shot.camera ?? {}) } };
    try {
      await runCommands(s.commands, s.name);
      if (s.time !== undefined && s.time !== lastTime) {
        await run('dev.time', { ticks: s.time });
        lastTime = s.time;
      }
      if (s.weather !== undefined && s.weather !== lastWeather) {
        await run('dev.weather', { weather: s.weather });
        lastWeather = s.weather;
      }
      if (s.camera && s.camera.x !== undefined) {
        const cam = { ...s.camera };
        if (cam.fov === undefined && s.fov !== undefined) cam.fov = s.fov;
        if (cam.mode === undefined && s.mode !== undefined) cam.mode = s.mode;
        await run('dev.camera', cam);
      }
      if (s.screen !== undefined) await run('dev.screen', { open: s.screen });
      if (s.delayMs) await new Promise((r) => setTimeout(r, s.delayMs));
      const shotRes = await run('dev.screenshot', {
        name: s.name,
        hideHud: s.hideHud ?? true,
        ...(s.frames !== undefined ? { frames: s.frames } : {}),
        ...(s.waitChunks !== undefined ? { waitChunks: s.waitChunks } : {}),
      });
      if (s.screen !== undefined && s.screen !== null && !s.keepScreen) await run('dev.screen', { open: null });
      const entry = { name: s.name, ok: true, path: shotRes.path, width: shotRes.width, height: shotRes.height, stats: shotRes.stats, ms: Date.now() - t0 };
      if (shotRes.chunksTimedOut) entry.warning = 'chunks did not finish building before the capture';
      if (shotRes.stats && shotRes.stats.meanLuma < 6) entry.warning = 'image is nearly black';
      results.push(entry);
      log(`${s.name}: ${shotRes.path} (${shotRes.width}x${shotRes.height}, luma ${shotRes.stats?.meanLuma}) ${entry.warning ?? ''}`);
    } catch (e) {
      results.push({ name: s.name, ok: false, error: e.message, ms: Date.now() - t0 });
      log(`${s.name}: FAILED ${e.message}`);
      // A hung game fails every later shot after long timeouts: stop and report it instead.
      const h = await dev.health();
      if (e.stalled || (h.ok && h.stalled) || !h.ok) {
        hung = h.ok ? `render thread stuck for ${h.msSinceLastFrame} ms` : `bridge not answering (${h.error})`;
        log(`aborting: the game looks hung (${hung}); relaunch it (dev.quit force-exits a hung game)`);
        break;
      }
    }
  }
} finally {
  dev.close();
}

const summary = { ok: !hung && results.length === shots.length && results.every((r) => r.ok), scene: path.resolve(sceneFile), totalMs: Date.now() - started, hung, shots: results };
if (manifest) fs.writeFileSync(manifest, JSON.stringify(summary, null, 2));
console.log(JSON.stringify(summary, null, 2));
process.exit(summary.ok ? 0 : 1);
