# Manager mode (main session = manager only)

Founder rule 2026-10-23, permanent. The main interactive session decides, splits,
delegates, merges, deploys, reads logs, reports. It does not implement.

## Enforcement

`PreToolUse` hook `.claude/hooks/manager-guard.py` (registered in `.claude/settings.json`
for `Edit|Write|MultiEdit|NotebookEdit|Bash`). Exit 2 + a message telling the session to delegate.

**Main vs subagent:** Claude Code puts `agent_id` (and `agent_type`) in the hook stdin JSON
only when the hook fires inside a subagent (docs: code.claude.com/docs/en/hooks, "Common
input fields"). No `agent_id` => main session => guarded. Subagents (also in
`.claude/worktrees/*`) are never restricted; Codex is a separate process with no Claude hooks.
The cwd/path is NOT used to decide. Hook errors fail open. Founder override: launch with
`ASTERA_MANAGER_MODE=off`.

## Main session may write

| Target | Why |
|---|---|
| `/tmp/claude-1000/**` (scratchpad) | prompts, temp files |
| `/home/node/.claude-accounts/*/**/memory/**` | memory |
| `/IdeaProjects/Astera/.claude/state/handoffs/<slug>.md` (primary checkout's state dir, not a `.claude/worktrees/*` copy; own track only; `rm` when the track is done) | handoff is the manager's job |
| `/dev/null`, `/dev/std{out,err}` | redirects |

Everything else (incl. `.claude/CLAUDE.md`, `.claude/settings.json`, hooks, rules) is blocked for Edit/Write/NotebookEdit and for Bash writers.

## Main session Bash

| Allowed | Blocked |
|---|---|
| `git` merge/commit/push/fetch/log/status/diff/show/worktree/branch/merge-base/rev-parse/add/stash/switch/checkout/restore --staged | `git mv/rm/apply/am/clean`, `git checkout -- p`, `git restore` (worktree), `reset --hard` |
| `make dev-content/dev-plugins/dev-deploy/dev-pack/docker-up/dev-hotswap*/help/check` | other `make` targets |
| `flock /tmp/claude-1000/astera-e2e.lock make e2e` (serialized) | |
| `flock … -c '…'` / `bash -c` (inner command is analysed recursively) | |
| `docker …`, `gh …`, read-only `codex exec …`, `scripts/codex-task.sh` (only with `run_in_background: true`) | |
| read-only: grep, rg, ls, cat, head, tail, find, wc, jq, git read cmds | `sed -i`, `perl -i`, `tee`, `dd of=`, `cp/mv/rm/ln/touch/mkdir/chmod/patch/rsync/unzip/install`, `tar x`, `curl -o`, `find -delete`, `>`/`>>` to repo paths |
| the above writing to the allowed targets above | `python3 -`/heredoc/`-c`/`node -e` code that opens files for writing (unless every absolute path literal is an allowed target) |

Known limit: `python3 script.py` / `bun x.ts` of an existing file is not inspected. Do not use it to dodge the rule; delegate.

## How to delegate

- Claude subagent: Agent tool (`astera-worker`, `general-purpose`, `isolation: worktree`); start the prompt with the contents of `.claude/prompts/agent-preamble.md`.
- Codex: write the task to a file under `/tmp/claude-1000/`, then launch `scripts/codex-task.sh <task-id> <prompt-file>` (optional 3rd arg = base ref) as ONE Bash call with `run_in_background: true`. Run it from the track worktree. The script runs Codex in the foreground (no `nohup`/`&`/`setsid`/`disown`; `CODEX_TIMEOUT` seconds, default 5400): it creates `.claude/worktrees/<task-id>` on `wp/<task-id>` from the invoking checkout's `HEAD`, prepends `.claude/prompts/codex-preamble.md`, runs `codex exec -p astar-implement -s danger-full-access`, writes `/tmp/claude-1000/codex/<task-id>.log` (ends with `EXIT=<code>`) and `<task-id>-last.md`, and exits with Codex's exit code.
  - Claude Code sends **one notification** when it exits. Then read the tail of `<task-id>.log` (`EXIT=`) and `<task-id>-last.md`, and merge `wp/<task-id>` into the track branch inside the track worktree (never in the primary checkout).
  - Never poll, `tail -f` or `sleep` while it runs. Parallel tasks = separate background Bash calls.
  - Gate: `.claude/hooks/gate-codex-lane.py` (PreToolUse/Bash, main session and subagents) denies the launcher without `run_in_background: true`, any detached codex/launcher, and raw write-capable `codex exec` (only `timeout N codex exec --sandbox read-only ...` is allowed directly). Fails open on errors. Ported from AstarHarness `gate-raw-codex-lane`; test: `bash .claude/hooks/gate-codex-lane.test.sh`.
- Harness changes (`.claude/**`, this rule included) also go through an agent.

## E2E (batch rule, founder 2026-10-26; supersedes the 2026-10-23 per-agent rule)

Tracks, agents and Codex do NOT run e2e. They add or update the e2e / game-sim test that proves their item and
name it in the report. The manager (or a QA subagent it launches) runs ONE `flock /tmp/claude-1000/astera-e2e.lock make e2e`
(serialized, ~12+ min, report `build/e2e-report.md`) per integration window (12:00 / 21:00 JST) on the merged
`wp/integrate` branch; see `.claude/rules/tracks.md`. An out-of-window single-track run needs the manager's OK
and a Board `e2e-request`. Never touch the live `astera-dev-*` containers. Compile-only Gradle tasks are
allowed; unit tests and `./gradlew check`/test tasks stay forbidden.
