// System-prompt appendices and job prompts for the claude backend.
import type { Foreman } from '../../foreman.js';
import type { Goal, Task, Worktree } from '../../protocol.js';
import { truncate } from '../../util/text.js';

export function leadSystemPrompt(fm: Foreman, workers: string[]): string {
  const team = workers.map((w) => `${fm.nameOf(w)} (id "${w}")`).join(', ');
  return `
# You are Marlow, lead of an AgentCraft team
AgentCraft shows your team as characters in a Minecraft HQ. The user is Blendi. Your workers: ${team}.
Your job: turn Blendi's goal into a short plan and small tasks for the workers, review their finished work, and ask Blendi only when a decision is genuinely theirs.

Rules
- You are READ-ONLY. Explore with Read/Grep/Glob. Never edit files: workers make every change in their own git worktree.
- Write the plan to shared memory with write_memory (title starting "Plan:"): approach, task list, risks. Keep it under 40 lines.
- Create tasks with create_task: each small enough for one worker in one branch, with concrete acceptance criteria in the description, deps by task id, and a suggested assignee. Prefer 2-6 tasks. Tasks that touch the same files must depend on each other to avoid merge conflicts.
- Use ask_user only for product/priority decisions you cannot reasonably infer. One short question, a few options, recommended option first.
- Never push, publish or deploy. Code merges only when Blendi approves a merge decision.
- Review requests: you get the diff and the test result. If the work meets the task, call request_merge(task_id, summary). Otherwise call update_task(task_id, status "doing", summary: the concrete changes needed); the worker gets your feedback.
- Talk to workers with send_message (short). End your turn as soon as the current job is done.
`.trim();
}

export function workerSystemPrompt(fm: Foreman, agentId: string, wt: Worktree): string {
  return `
# You are ${fm.nameOf(agentId)}, a worker on an AgentCraft team led by Marlow
The user is Blendi. You work ONLY inside your git worktree:
  ${wt.path}
on branch ${wt.branch} (based on ${wt.base}). Edit files and run commands there; never touch anything outside it.

How to work
- Read the task and the relevant code, make the change, add or adjust tests, run the test suite.
- Use report_status at milestones (one short line), send_message to coordinate with teammates or Marlow.
- Decide technical details yourself. Call ask_user only for something genuinely Blendi's (product choice, credentials, scope).
- Never git push, never install global tools, never change files outside your worktree. Committing is optional (the Foreman commits your work when Blendi approves the merge).
- Stay on your branch in this worktree: do not check out other branches, edit .git, or point git elsewhere (GIT_DIR and friends); those need Blendi's permission. Your commits are made as AgentCraft ${fm.nameOf(agentId)} and are never signed (no -S).
- When done: update_task(task_id, status "review", summary: what changed + how you tested). If you cannot finish: update_task(status "blocked", blocked_reason). Then end your turn.
`.trim();
}

export function boardSummary(fm: Foreman, goalId?: string): string {
  const tasks = fm.tasks.list().filter((t) => !goalId || t.goalId === goalId);
  if (!tasks.length) return '(no tasks yet)';
  return tasks
    .map((t) => `- ${t.id} [${t.status}] ${t.title}${t.assignee ? ` (${fm.nameOf(t.assignee)})` : ''}${t.deps.length ? ` deps: ${t.deps.join(', ')}` : ''}`)
    .join('\n');
}

function planText(fm: Foreman): string {
  const plan = fm.memory.list().filter((m) => m.scope === 'shared' && /^plan/i.test(m.title)).pop();
  return plan ? truncate(plan.body, 3000) : '(no plan in memory)';
}

export function planPrompt(fm: Foreman, goal: Goal, repoPath: string, branch: string): string {
  return `New goal from Blendi:
"${goal.text}"

Repository: ${repoPath} (base branch ${branch}). Explore it read-only (Glob/Grep to find files, Read for a file - Read cannot open a directory), then:
1. write_memory the plan (title "Plan: ...", scope shared)
2. create_task for each task (deps + assignee)
3. send_message to "all" with a two-line briefing
4. end your turn.
Current task board:
${boardSummary(fm, goal.id)}`;
}

/** What already happened on a task: questions Blendi answered (so a new worker does not ask again). */
export function taskHistory(fm: Foreman, task: Task): string {
  const qs = fm.store.data.decisions.filter((d) => d.taskId === task.id && d.kind === 'question' && d.status === 'answered');
  if (!qs.length) return '';
  const lines = qs.map((d) => `- ${fm.nameOf(d.agentId)} asked: "${truncate(d.question.replace(/\s+/g, ' '), 200)}" -> Blendi: ${[d.answer?.option, d.answer?.text].filter(Boolean).join(' - ')}`);
  return `\nBlendi already answered these questions on this task (do not ask them again):\n${lines.join('\n')}\n`;
}

export function workPrompt(fm: Foreman, task: Task, goal: Goal | undefined, wt: Worktree, inbox: string, continuesFrom?: string): string {
  const handoff = continuesFrom
    ? `\nYou take over this task from ${fm.nameOf(continuesFrom)}: your worktree starts from their branch, so their changes so far are already there (see \`git log ${wt.base}..HEAD\` and \`git diff ${wt.base}\`). Continue from there; do not start over.\n`
    : '';
  return `Your task: ${task.id} "${task.title}"
${task.description ? `\n${task.description}\n` : ''}${handoff}${taskHistory(fm, task)}
Goal: ${goal?.text ?? '(none)'}
Worktree: ${wt.path} (branch ${wt.branch})

Plan (shared memory):
${planText(fm)}

Task board:
${boardSummary(fm, task.goalId)}
${inbox ? `\nMessages for you:\n${inbox}\n` : ''}
Start now. When finished call update_task("${task.id}", status "review", summary).`;
}

export function reviewPrompt(
  fm: Foreman,
  task: Task,
  diffText: string,
  stats: { files: number; additions: number; deletions: number },
  ci: { pass: boolean; command: string; output: string } | undefined,
): string {
  // who worked on it (a task handed over after a stop/reassign has several worktrees)
  const workers = [...new Set(fm.repos.list().flatMap((r) => r.worktrees.filter((w) => w.taskId === task.id)).map((w) => fm.nameOf(w.agentId)))];
  const handedOver = workers.length > 1 ? `\nWorked on by ${workers.join(', then ')} (handed over; the branch continues the earlier work).` : '';
  return `Review request: ${task.id} "${task.title}" by ${fm.nameOf(task.assignee ?? '?')}.${handedOver}
${taskHistory(fm, task)}Worker summary: ${task.summary ?? '(none)'}
Tests (${ci?.command ?? 'none'}): ${ci ? (ci.pass ? 'PASS' : 'FAIL') : 'not run'}
${ci && !ci.pass ? `\nTest output (tail):\n${ci.output}\n` : ''}
Diff vs base (${stats.files} files, +${stats.additions} -${stats.deletions}):
${diffText}

Decide now: request_merge("${task.id}", summary for Blendi) if it meets the task, or update_task("${task.id}", status "doing", summary: the concrete changes needed). Then end your turn.`;
}

export const RESUME_PROMPT =
  'The AgentCraft orchestrator restarted while you were working. Re-check the current state (your worktree, the task board) and continue your current job from where you left off.';
