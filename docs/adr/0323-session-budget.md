---
status: Accepted
date: 2026-11-08
deciders: Founder, ops-harness
supersedes: null
superseded_by: null
---

# ADR-0323: Session budget (long sessions are structurally refused)

## Status

Accepted

## Context

On 2026-11-08 the integration manager ran its work in one long session. Every turn re-read about 120k tokens of context and the session compacted 193 times in a single day. Astera runs on a flat-rate plan, so the cost is not money but hitting the usage limits faster; compaction also loses state silently. A rule that depends on the manager remembering to restart is not enough.

## Decision

One task per session. Start every task with /clear or a new session; delegate investigation and implementation to subagents/Codex. Enforced by hooks in `.claude/hooks/session-budget.py`:

- UserPromptSubmit: context >= 120k tokens warns (user message + model context: write the handoff, restart); context >= 200k or >= 2 compactions blocks the prompt. Prompts mentioning handoff / 引き継ぎ / `/handoff` (and `/clear`) always pass. Token count = last main-thread assistant `usage` (input + cache_read + cache_creation) from the transcript, fallback bytes/4.
- SessionStart (matcher `compact`): per-session compaction counter in `.claude/state/session-budget/<session_id>.json`; from the 2nd compaction the model is told to write the handoff and stop.
- Statusline `ctx <N>k / compactions <M>` (`statusline-budget.sh`).
- `ASTERA_SESSION_BUDGET=off` disables; any internal error fails open.
- Every integration window is a fresh session that reads `/IdeaProjects/Astera/.claude/state/handoffs/integrate-qa.md`, runs the window, rewrites that handoff and ends.
- Subagent/Codex final reports are at most 20 lines; detail goes to a file and only its path is returned.

## Consequences

### Positive
- Context per turn stays bounded; usage limits last longer; state lives in handoffs, not compaction summaries.

### Negative / Trade-offs
- More session restarts; handoff discipline becomes load-bearing.

### Mitigations
- Handoff prompts are exempt from the block; the founder can override with the env var.

## Alternatives Considered

- **Rule text only**: already failed (193 compactions).
- **Scheduled headless integration runner**: separate decision, not made here.

## References

- `.claude/CLAUDE.md` section "Session budget", `.claude/rules/tracks.md`
