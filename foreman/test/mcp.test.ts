import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { loadConfig } from '../src/config.js';
import { externalMcpFor, matchesTool, mcpRulesFor, parseMcpServers } from '../src/mcp.js';
import { classifyToolUse, type PolicyContext } from '../src/policy.js';
import { rmrf, tempDir } from './helpers.js';

let home: string | undefined;
afterEach(() => {
  if (home) rmrf(home);
  home = undefined;
});

const servers = parseMcpServers(
  {
    'sage-read': { command: 'C:\\mcp\\sage-read-mcp.exe', env: { SAGECHAT_HOME: '%SAGE_HOME%' }, roles: ['lead', 'worker'], allow: ['*'] },
    'sage-write': { command: 'sage-write-mcp.exe', agents: ['kit'], allow: ['get_*', 'run_select'], deny: ['print_form'] },
    outlook: { command: 'outlook-mcp.exe', deny: ['send_email'] },
    off: { command: 'x.exe', enabled: false },
    remote: { type: 'http', url: 'http://127.0.0.1:9000/mcp' },
  },
  'test',
  { SAGE_HOME: 'C:\\sagemcp' },
);

describe('external MCP config', () => {
  it('validates entries and expands %VAR%', () => {
    expect((servers['sage-read'] as { env: Record<string, string> }).env.SAGECHAT_HOME).toBe('C:\\sagemcp');
    expect(() => parseMcpServers({ agentcraft: { command: 'x' } }, 't')).toThrow(/reserved/);
    expect(() => parseMcpServers({ 'a__b': { command: 'x' } }, 't')).toThrow(/bad MCP server name/);
    expect(() => parseMcpServers({ x: { args: [] } }, 't')).toThrow(/MCP server "x"/);
    expect(() => parseMcpServers({ x: { type: 'http', url: 'nope' } }, 't')).toThrow(/MCP server "x"/);
  });

  it('gives each agent only its servers, without the AgentCraft keys', () => {
    expect(Object.keys(externalMcpFor(servers, 'marlow', 'lead'))).toEqual(['sage-read']);
    expect(Object.keys(externalMcpFor(servers, 'kit', 'worker')).sort()).toEqual(['outlook', 'remote', 'sage-read', 'sage-write']);
    expect(Object.keys(externalMcpFor(servers, 'wren', 'worker')).sort()).toEqual(['outlook', 'remote', 'sage-read']);
    expect(externalMcpFor(servers, 'kit', 'worker')['sage-write']).toEqual({ command: 'sage-write-mcp.exe' });
  });

  it('matches tool globs literally apart from *', () => {
    expect(matchesTool(['get_*'], 'get_item')).toBe(true);
    expect(matchesTool(['get_*'], 'xget_item')).toBe(false);
    expect(matchesTool(['a.b'], 'axb')).toBe(false);
  });

  it('reads claude.mcpServers from config.json and --mcp-config on top', () => {
    const dir = (home = tempDir());
    fs.writeFileSync(path.join(dir, 'config.json'), JSON.stringify({ claude: { mcpServers: { a: { command: 'a.exe' }, b: { command: 'b.exe' } } } }));
    const file = path.join(dir, 'mcp.json');
    fs.writeFileSync(file, JSON.stringify({ mcpServers: { b: { command: 'b2.exe' } } }));
    const cfg = loadConfig(['--home', dir, '--mcp-config', file], {});
    expect(cfg.claude.mcpServers).toEqual({ a: { command: 'a.exe' }, b: { command: 'b2.exe' } });
    expect(loadConfig(['--home', dir], {}).claude.mcpServers.b).toEqual({ command: 'b.exe' });
    expect(() => loadConfig(['--home', dir, '--mcp-config', path.join(os.tmpdir(), 'missing-agentcraft-mcp.json')], {})).toThrow(/MCP config/);
  });
});

describe('external MCP permission policy', () => {
  const ctx = (agentId: string, role: 'lead' | 'worker'): PolicyContext => ({ role, cwd: os.tmpdir(), tempDirs: [], externalMcp: mcpRulesFor(servers, agentId, role) });

  it('allows listed read tools, refuses denied ones, asks for the rest', () => {
    expect(classifyToolUse('mcp__sage-read__query_sqlite', {}, ctx('marlow', 'lead')).action).toBe('allow');
    expect(classifyToolUse('mcp__sage-write__get_item', {}, ctx('kit', 'worker')).action).toBe('allow');
    expect(classifyToolUse('mcp__sage-write__print_form', {}, ctx('kit', 'worker')).action).toBe('deny');
    expect(classifyToolUse('mcp__outlook__send_email', {}, ctx('kit', 'worker')).action).toBe('deny');
    const v = classifyToolUse('mcp__sage-write__create_sales_order', {}, ctx('kit', 'worker'));
    expect(v.action).toBe('ask');
    expect(v.action === 'ask' && v.ruleKeys).toEqual(['mcp__sage-write__create_sales_order']);
  });

  it('refuses servers the agent was not given', () => {
    expect(classifyToolUse('mcp__sage-write__get_item', {}, ctx('wren', 'worker')).action).toBe('deny');
    expect(classifyToolUse('mcp__outlook__search_emails', {}, ctx('marlow', 'lead')).action).toBe('deny');
    expect(classifyToolUse('mcp__agentcraft__send_message', {}, ctx('marlow', 'lead')).action).toBe('allow');
  });

  it('"Always allow" still covers an asked tool', () => {
    const c = { ...ctx('kit', 'worker'), alwaysAllow: ['mcp__sage-write__create_sales_order'] };
    expect(classifyToolUse('mcp__sage-write__create_sales_order', {}, c).action).toBe('allow');
  });
});
