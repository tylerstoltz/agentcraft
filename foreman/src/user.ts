// The people the team works for. Each intent carries the Minecraft player who sent it (the mod's
// `hello.player`; on a multiplayer server the server stamps the authenticated player name), so goals,
// messages, answers and actions name whoever did them. A configured name (--user-name /
// AGENTCRAFT_USER_NAME / config.json "userName") is the fallback for clients without a player (CLI
// tools) and for generic references in prompts; without one it is "the user". The OS account name is
// never used: on a shared server it would be the host's login, not the person playing.

let current: string | undefined;

/** Trim and cap a display name; undefined when nothing is left. */
export function cleanName(name: string | undefined): string | undefined {
  const n = name?.replace(/[\p{Cc}\p{Cf}]/gu, '').trim();
  return n ? n.slice(0, 40) : undefined;
}

/** Set once at Foreman start from the config. */
export function setUserName(name: string | undefined): void {
  current = cleanName(name);
}

/** The configured name, if any. */
export function configuredUserName(): string | undefined {
  return current ?? cleanName(process.env.AGENTCRAFT_USER_NAME);
}

/** How agents refer to the user in general: the configured name, else "the user". */
export function userName(): string {
  return configuredUserName() ?? 'the user';
}

/** Who did something: the player's name, else userName(). */
export function who(by: string | undefined): string {
  return cleanName(by) ?? userName();
}

/** who() for the start of a sentence: "The user answered ..." (player names keep their case). */
export function Who(by: string | undefined): string {
  const n = cleanName(by) ?? configuredUserName();
  return n ?? 'The user';
}

/** One line for system prompts: who the agents work for. */
export function userLine(): string {
  const n = configuredUserName();
  return n
    ? `The user is ${n}.`
    : 'The user is whoever plays in the HQ (on a shared server, several players); each goal, message and answer says who sent it.';
}
