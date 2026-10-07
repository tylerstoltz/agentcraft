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

describe('user name', () => {
  it('comes from --user-name, then AGENTCRAFT_USER_NAME, then config.json, else stays unset', async () => {
    const fs = await import('node:fs');
    const path = await import('node:path');
    expect(load(['--user-name', 'Sam']).userName).toBe('Sam');
    home = tempDir();
    expect(loadConfig(['--home', home], { AGENTCRAFT_USER_NAME: 'Robin' }).userName).toBe('Robin');
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ userName: 'Kai' }));
    expect(loadConfig(['--home', home], {}).userName).toBe('Kai');
    rmrf(home);
    // never the OS account: on a shared server that is the host's login, not the player
    expect(load([]).userName).toBeUndefined();
  });

  it('is sent to the mod in foreman.status and used in prompts', async () => {
    const { makeForeman } = await import('./helpers.js');
    const { leadSystemPrompt } = await import('../src/agents/claude/prompts.js');
    home = tempDir();
    const h = makeForeman(home, ['--user-name', 'Sam']);
    try {
      expect(h.fm.status.userName).toBe('Sam');
      expect(h.fm.nameOf('user')).toBe('Sam');
      expect(leadSystemPrompt(h.fm, ['kit'])).toContain('The user is Sam.');
    } finally {
      await h.fm.close();
    }
  });

  it('without a configured name, nothing names the OS account', async () => {
    const os = await import('node:os');
    const { Foreman } = await import('../src/foreman.js');
    const { silentLogger } = await import('../src/context.js');
    const { leadSystemPrompt, workerSystemPrompt } = await import('../src/agents/claude/prompts.js');
    const { setUserName } = await import('../src/user.js');
    const saved = process.env.AGENTCRAFT_USER_NAME;
    delete process.env.AGENTCRAFT_USER_NAME;
    home = tempDir();
    const fm = new Foreman({ config: loadConfig(['--home', home, '--no-notify'], {}), logger: silentLogger });
    try {
      expect(fm.status.userName).toBeUndefined();
      expect(fm.nameOf('user')).toBe('the user');
      const lead = leadSystemPrompt(fm, ['kit']);
      const worker = workerSystemPrompt(fm, 'kit', { id: 'kit-t1', agentId: 'kit', branch: 'b', base: 'main', path: '/w', status: 'active', ahead: 0, files: 0, additions: 0, deletions: 0 });
      expect(lead).toContain('The user is whoever plays in the HQ');
      const login = os.userInfo().username;
      if (login.length > 2) {
        for (const text of [lead, worker]) expect(text.toLowerCase()).not.toContain(login.toLowerCase());
      }
    } finally {
      await fm.close();
      setUserName(undefined);
      if (saved === undefined) delete process.env.AGENTCRAFT_USER_NAME;
      else process.env.AGENTCRAFT_USER_NAME = saved;
    }
  });
});

describe('player attribution', () => {
  it('names the player who sent each intent, not the configured user', async () => {
    const { makeForeman } = await import('./helpers.js');
    const { formatInbox } = await import('../src/bus.js');
    const { planPrompt, taskHistory } = await import('../src/agents/claude/prompts.js');
    home = tempDir();
    const h = makeForeman(home, ['--user-name', 'Sam']);
    const reply = () => undefined;
    try {
      // a goal set by Steve
      const goal = h.fm.createGoal('Add tags', undefined, 'Steve');
      expect(goal.by).toBe('Steve');
      expect(planPrompt(h.fm, goal, '/repo', 'main')).toMatch(/^New goal from Steve:/);
      expect(h.fm.store.data.feed.at(-1)).toMatchObject({ kind: 'goal', agentId: 'user', by: 'Steve' });

      // a message from Alex
      await h.fm.handle({ v: 1, type: 'user.message', to: 'marlow', text: 'use sqlite' }, reply, { player: 'Alex' });
      const inbox = h.fm.bus.inbox('marlow');
      expect(inbox.at(-1)).toMatchObject({ from: 'user', by: 'Alex', text: 'use sqlite' });
      expect(formatInbox(inbox, (id) => h.fm.nameOf(id))).toContain('- from the user (Alex): use sqlite');
      expect(h.fm.store.data.feed.at(-1)).toMatchObject({ kind: 'user', to: 'marlow', by: 'Alex' });

      // Steve answers a question on a task
      const t = h.fm.tasks.create({ title: 'parser', createdBy: 'marlow', goalId: goal.id });
      const d = h.fm.createDecision({ agentId: 'kit', kind: 'question', question: 'Hyphens?', options: ['Yes', 'No'], taskId: t.id });
      await h.fm.handle({ v: 1, type: 'decision.answer', decisionId: d.id, option: 'Yes' }, reply, { player: 'Steve' });
      expect(h.fm.decisions.get(d.id)?.answer?.by).toBe('Steve');
      expect(h.fm.store.data.feed.find((f) => f.kind === 'decision' && f.text.startsWith('Steve answered'))).toMatchObject({ by: 'Steve' });
      expect(taskHistory(h.fm, h.fm.tasks.require(t.id))).toContain('-> Steve: Yes');

      // Alex cancels it
      await h.fm.handle({ v: 1, type: 'task.action', taskId: t.id, action: 'cancel' }, reply, { player: 'Alex' });
      expect(h.fm.store.data.feed.at(-1)).toMatchObject({ text: `Task ${t.id} cancelled by Alex: parser`, by: 'Alex' });

      // a client without a player (CLI) falls back to the configured name
      await h.fm.handle({ v: 1, type: 'user.message', to: 'marlow', text: 'hi' }, reply);
      expect(formatInbox(h.fm.bus.inbox('marlow').slice(-1), (id) => h.fm.nameOf(id))).toBe('- from the user (Sam): hi');
    } finally {
      await h.fm.close();
    }
  });
});
