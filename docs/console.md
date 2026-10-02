# Console, decisions, permissions and HUD

The practical core of AgentCraft: how you talk to the team and answer them without leaving the
game. Code: `mod/src/client/java/dev/agentcraft/client/{console,decisions,permissions,hud}`.
Shots: `tools/scenes/console.json` (showcase busy state).

## Keys (Options > Controls > Key Binds > AgentCraft, all rebindable)

| key | does |
| --- | --- |
| `` ` `` | open the console (again with an empty input: close it) |
| `Enter` while looking at a console terminal | open the console (a right-click on the terminal does too) |
| `J` | open the decision queue (a right-click on the Decision Podium does too) |

Neither screen pauses the game: agents keep walking and working behind it.

## Console

A paper command bar at the bottom (brass `>`, the caret, ghost completion) and a live feed panel
above it: the Foreman feed merged with the console's own lines, each agent in its colour (face,
name colour, a colour stripe), time separators between minutes, "needs you" lines in clay (click
one to open the decision queue; Esc comes back to the console). The right end of the bar always
says what Enter will do ("message Juniper", "new goal → pocket-notes", "answer d4: Merge") or
why it cannot ("no agent named @xyz (marlow, juniper, ...)"). After Enter the bar clears at once
and the same spot shows the Foreman's ack ("sent to Juniper ✓"); a refusal shows a red strip above
the bar, a line in the feed, and puts your text back so you can fix it.

| input | sends |
| --- | --- |
| plain text | `goal.submit` (with several repos you pick one first: 1-9 / arrows, Enter) |
| `@juniper text`, `@all text` | `user.message` |
| `/answer [d4] <n\|option> [text]` | `decision.answer`; `n` is the 1-based button number; the id can be left out when one decision is open; free text for questions; `Request changes` needs the feedback text |
| `/repo add <path>`, `/repos` | `repo.add`, list repos |
| `/pause @x`, `/resume @x`, `/stop @x` (also `all`), `/spawn @x [task]` | `agent.action` |
| `/task <id> cancel\|retry\|prioritize [n]\|reassign @x` | `task.action` |
| `/diff [worktree\|@agent\|task]` | opens the diff screen (`diff`, owned by the diff feature); without one, prints a file summary from `diff.request` |
| `/status`, `/help`, `/decide`, `/clear`, `/sound on\|off` | local |

Tab completes agent names (also after `/pause` etc.), commands, decision ids and options, task ids
and worktrees; repeated Tab cycles, Up/Down move in the popup. Up/Down otherwise walk the history
(kept in `<game dir>/agentcraft/console-history.txt`). Shift+Enter adds a line; a multi-line
paste grows the bar (up to 6 lines, then it scrolls).

## Decision screen

One decision at a time, in queue order: permission prompts first (an agent is blocked mid
tool-call), then questions, then merges, oldest first. The brass frame shows the asking agent
(framed portrait, name, kind, age, task), the question, the context in a scrollable well and the
options as buttons.

| key | does |
| --- | --- |
| `1`-`9` | choose that option at once |
| Enter / Space | the highlighted option (arrows move the highlight) |
| typing | starts a free-text answer (questions); Enter sends it, Shift+Enter adds a line |
| Tab / Shift+Tab | next / previous decision (also the ‹ › keys in the header) |
| `D` | merge: review the diff |
| Esc | later (or leave the text field) |

Merges show branch → base, files, +/− and the test result, the per-file list (fetched with
`diff.request`), the worker's summary and Rowan's review. "Request changes" opens the feedback field
(Enter sends it to the worker), "Reject" asks for a second press (it abandons the branch). After an
answer the chosen button stays pressed with a ✓, the next decision comes up, and the screen closes
itself when the queue is empty. A decision answered elsewhere (console, another client) is shown as
such and skipped.

## Permission prompts

The body of a permission decision: the tool and the exact command in a well with a risk stripe, a
risk chip (low brass / medium clay / high red, from the Foreman's reason: deletes, writes outside
the worktree, publishing, credentials are high; network, installs, new branches are medium; reads
and `cd` outside are low), the reason, the working directory, and what "Always allow for this agent"
covers (tied to key 2). Buttons: Allow once (1), Always allow (2, sends the exact label
`Always allow for this agent`), Deny (3).

## HUD

- Goal bar (top centre, boss-bar style): status dot, goal text, %, progress bar, open task counts
  by column and "n/m done". With no goal yet: "No goal yet · press ` to give the team one".
- Decisions badge under it: "2 waiting · press J" with a pulsing clay dot.
- Paper toasts for `notify` (top right, under the connection pill, below the goal bar on narrow
  screens): the agent's portrait, "Marlow needs you", two lines, the J hint; they leave early once
  their decision is answered. Hidden while the decision screen is open.
- Sounds: a soft note-block bell when a decision opens, a chime when a task is done, a bigger chime
  when the goal completes. Never on a reconnect snapshot. Off when the game runs muted
  (`AGENTCRAFT_MUTE=1`, the dev/QA default) or after `/sound off`.

## In the world

- Decision Podium: while decisions wait, a speech bubble over the podium shows the count, the key,
  the first decision's agent and its question (it grows with distance so it reads across the
  room), and the podium's `open` block state (lit paper, lens, bell) follows "any decision open".
- Console terminal: the leaning screen shows a prompt with your last command, how to open the
  console, and what is waiting.

## QA hooks (DevBridge)

| command | |
| --- | --- |
| `dev.screen {open:"console"}` + `dev.type "@ju"` | console with the autocomplete |
| `dev.console {prefill?, submit?, open?}` | open the console with text (and press Enter); returns value, ghost, completions, ack stats |
| `dev.console.parse {text}` | what an input would do (intent, completions), nothing is sent |
| `dev.screen {open:"decision"}`, `dev.decision {decisionId?\|kind?\|preview?}` | the decision screen (queue head / one decision / a sample permission marked "preview") |
| `dev.screen {open:"permission"}` | the oldest open permission prompt, else the marked preview |
| `dev.decisions` | the queue in HUD order and the open decision screen's state |
| `dev.toast {text, level?, decisionId?}`, `dev.hud.state` | a toast without the Foreman; what the HUD shows (waiting, toasts, sound counters) |

Note: `id` is a reserved request field, hence `decisionId`.
