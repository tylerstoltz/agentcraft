import { afterEach, describe, expect, it } from 'vitest';
import { loadConfig } from '../src/config.js';
import { rmrf, tempDir } from './helpers.js';

let home: string | undefined;
afterEach(() => {
  if (home) rmrf(home);
  home = undefined;
});

function load(args: string[]) {
  home = tempDir();
  return loadConfig(['--home', home, ...args], {});
}

describe('loadConfig argument checking', () => {
  it('accepts the documented claude flags', () => {
    const cfg = load(['--backend', 'claude', '--workers', 'juniper,kit', '--model', 'sonnet', '--effort', 'low', '--no-notify', '--max-budget', '2']);
    expect(cfg.claude.workers).toEqual(['juniper', 'kit']);
    expect(cfg.claude.leadModel).toBe('sonnet');
    expect(cfg.claude.workerModel).toBe('sonnet');
    expect(cfg.claude.effort).toBe('low');
    expect(cfg.claude.leadEffort).toBe('low');
    expect(cfg.notify).toBe(false);
  });

  it('accepts the sim flags launch.ps1 passes', () => {
    const cfg = load(['--backend', 'sim', '--profile', 'x', '--port', '41000', '--reset', '--showcase', 'late', '--speed', '2', '--autostart']);
    expect(cfg.sim.showcaseAt).toBe('showcase-late');
    expect(cfg.sim.speed).toBe(2);
  });

  // regression: PowerShell `-File launch.ps1 -ForemanArgs '--workers,juniper,kit,--model,sonnet'` hands
  // the Foreman ONE argument; it used to be ignored silently and the team started on opus/medium/3 workers
  it('refuses an argument that PowerShell joined with commas', () => {
    expect(() => load(['--backend', 'claude', '--workers,juniper,kit,--model,sonnet,--effort,low'])).toThrow(/unknown option "--workers,juniper,kit,--model,sonnet,--effort,low"/);
  });

  it('refuses mistyped flags and stray positionals', () => {
    expect(() => load(['--wokers', 'kit'])).toThrow(/unknown option "--wokers"/);
    expect(() => load(['--backend', 'sim', 'oops', 'extra'])).toThrow(/unexpected argument "oops"/);
  });

  it('refuses an unknown effort instead of falling back to medium', () => {
    expect(() => load(['--effort', 'lo'])).toThrow(/unknown effort "lo"/);
  });
});
