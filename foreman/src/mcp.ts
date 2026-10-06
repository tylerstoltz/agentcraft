// External MCP servers: tools on the Foreman's host (Sage, Outlook, Blender, ...) given to agents.
//
// Configured in config.json `claude.mcpServers` and/or a separate file (--mcp-config, env
// AGENTCRAFT_MCP_CONFIG) shaped like a `.mcp.json` ({ "mcpServers": { ... } }). Each entry is the
// SDK's McpServerConfig (stdio / http / sse) plus AgentCraft-only keys, stripped before the SDK
// sees it:
//   roles    which agents get it: 'lead' and/or 'worker' (default: workers only, the lead is read-only)
//   agents   only these agent ids (on top of roles)
//   allow    tool names / globs that run without asking (read-only tools)
//   deny     tool names / globs that are always refused (wins over allow)
//   enabled  false: ignore the entry
// Every other tool of the server asks in-world (policy.ts); "Always allow" covers that one tool.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import type { McpServerConfig } from '@anthropic-ai/claude-agent-sdk';
import { z } from 'zod';

const common = {
  roles: z.array(z.enum(['lead', 'worker'])).optional(),
  agents: z.array(z.string()).optional(),
  allow: z.array(z.string()).optional(),
  deny: z.array(z.string()).optional(),
  enabled: z.boolean().optional(),
};

const ServerSchema = z.union([
  z.object({ type: z.literal('stdio').optional(), command: z.string().min(1), args: z.array(z.string()).optional(), env: z.record(z.string(), z.string()).optional(), ...common }),
  z.object({ type: z.enum(['http', 'sse']), url: z.string().url(), headers: z.record(z.string(), z.string()).optional(), ...common }),
]);

export type ExternalMcpServer = z.infer<typeof ServerSchema>;

/** Per-server allow/deny lists, for the permission policy. */
export type McpRules = Record<string, { allow: string[]; deny: string[] }>;

/** our in-process server's name: never overridden by config */
export const RESERVED_MCP_NAME = 'agentcraft';

/** `~` and `%VAR%` / `${VAR}` in commands, args, env values and urls. */
function expand(s: string, env: NodeJS.ProcessEnv): string {
  const home = s === '~' || s.startsWith('~/') || s.startsWith('~\\') ? os.homedir() + s.slice(1) : s;
  return home.replace(/%([A-Za-z0-9_]+)%|\$\{([A-Za-z0-9_]+)\}/g, (m, a?: string, b?: string) => env[(a ?? b)!] ?? m);
}

/** Validates a `mcpServers` object; throws a readable error naming the bad entry. */
export function parseMcpServers(raw: unknown, source: string, env: NodeJS.ProcessEnv = process.env): Record<string, ExternalMcpServer> {
  if (raw === undefined || raw === null) return {};
  if (typeof raw !== 'object' || Array.isArray(raw)) throw new Error(`${source}: mcpServers must be an object`);
  const out: Record<string, ExternalMcpServer> = {};
  for (const [name, v] of Object.entries(raw as Record<string, unknown>)) {
    // tool names are mcp__<server>__<tool>: a `__` in the name would make them ambiguous
    if (!/^[A-Za-z0-9_-]+$/.test(name) || name.includes('__')) throw new Error(`${source}: bad MCP server name "${name}" (letters, digits, - and single _)`);
    if (name === RESERVED_MCP_NAME) throw new Error(`${source}: the MCP server name "${RESERVED_MCP_NAME}" is reserved`);
    const r = ServerSchema.safeParse(v);
    if (!r.success) throw new Error(`${source}: MCP server "${name}": ${r.error.issues.map((i) => `${i.path.join('.') || '(entry)'} ${i.message}`).join('; ')}`);
    const s = r.data;
    if ('command' in s) {
      out[name] = {
        ...s,
        command: expand(s.command, env),
        ...(s.args ? { args: s.args.map((a) => expand(a, env)) } : {}),
        ...(s.env ? { env: Object.fromEntries(Object.entries(s.env).map(([k, x]) => [k, expand(x, env)])) } : {}),
      };
    } else out[name] = { ...s, url: expand(s.url, env) };
  }
  return out;
}

/** The `mcpServers` of a `.mcp.json`-style file ({ "mcpServers": {...} }, or the bare object). */
export function readMcpConfigFile(file: string, env: NodeJS.ProcessEnv = process.env): Record<string, ExternalMcpServer> {
  let json: unknown;
  try {
    json = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    throw new Error(`MCP config ${file}: ${(e as Error).message}`);
  }
  const o = json as { mcpServers?: unknown };
  return parseMcpServers(o && typeof o === 'object' && 'mcpServers' in o ? o.mcpServers : json, path.basename(file), env);
}

function applies(s: ExternalMcpServer, agentId: string, role: 'lead' | 'worker'): boolean {
  if (s.enabled === false) return false;
  if (!(s.roles ?? ['worker']).includes(role)) return false;
  return !s.agents || s.agents.includes(agentId);
}

/** The servers this agent gets, as SDK McpServerConfig (AgentCraft keys stripped). */
export function externalMcpFor(servers: Record<string, ExternalMcpServer>, agentId: string, role: 'lead' | 'worker'): Record<string, McpServerConfig> {
  const out: Record<string, McpServerConfig> = {};
  for (const [name, s] of Object.entries(servers)) {
    if (!applies(s, agentId, role)) continue;
    const { roles: _r, agents: _a, allow: _al, deny: _d, enabled: _e, ...sdk } = s;
    out[name] = sdk as McpServerConfig;
  }
  return out;
}

/** allow/deny lists of the servers this agent gets (absent server = not configured for it). */
export function mcpRulesFor(servers: Record<string, ExternalMcpServer>, agentId: string, role: 'lead' | 'worker'): McpRules {
  const out: McpRules = {};
  for (const [name, s] of Object.entries(servers)) if (applies(s, agentId, role)) out[name] = { allow: s.allow ?? [], deny: s.deny ?? [] };
  return out;
}

/** `*` matches any run of characters; everything else is literal. Case-sensitive, like tool names. */
export function matchesTool(patterns: string[], tool: string): boolean {
  return patterns.some((p) => new RegExp(`^${p.split('*').map((x) => x.replace(/[.+?^${}()|[\]\\]/g, '\\$&')).join('.*')}$`).test(tool));
}

/** One line for the system prompt: which host tools this agent has. */
export function mcpPromptNote(servers: Record<string, McpServerConfig>): string {
  const names = Object.keys(servers);
  if (!names.length) return '';
  return `\n\nHost tools: you also have these MCP servers on the Foreman's machine: ${names.join(', ')}. Follow each server's own instructions. Read-only calls may run directly; any call that changes something (creating, updating, sending, printing) pauses for approval in the world, so first use ask_user to show exactly what will be written and get a yes.`;
}
