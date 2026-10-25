---
name: wave-start
description: Bootstrap a track session by reading VISION.md, the area spec and the Board in order and summarising scope, owned paths, hot files and open items. Fires when the user starts a track ("ui-access を始めて", "track modes-combat"), says "Wave Nx" (legacy wording), or invokes /wave-start. Does not spawn workers - primes manager context only.
allowed-tools: Read, Bash, Glob, Grep
---

# wave-start - track session priming

Wave vocabulary is retired (ADR-0255). Work is organised by track; this skill
primes a session for one track.

## Step 0 - Be in the track's worktree

Slug = the track name (`ui-access`, `modes-combat`, `economy-life`,
`presentation`, `skills-vfx`, `builds-maps`, `bots-qa`, `ops-harness`).
Worktree: `/IdeaProjects/Astera/.claude/worktrees/<slug>`, branch `wp/<slug>`.

- If `/IdeaProjects/Astera/.claude/state/handoffs/<slug>.md` exists, read it first and resume.
- Otherwise create the worktree from `origin/main` (after `fetch`) and work there.

## Step 1 - Read in this order

1. `docs/vision/VISION.md`
2. `docs/vision/areas/<area>.md` for the track's area
3. The Board (https://claude.ai/artifact/2D2a8S41RpDwApdZFnPno3) via `ArtifactData`:
   items of this area, `founder-check` / `blocked` items, `memos` with status `new`.
4. `.claude/rules/tracks.md` (owned paths, hot files) and `.claude/rules/board.md`

## Step 2 - Summary (one screen)

```
## Track <slug> - bootstrap summary
**Area spec:** <path> - 現状 in 2 lines
**Open items (by rank):** <ID title lane> ...
**Founder memos / blocked questions:** <list or none>
**Owned paths / hot files:** <from tracks.md>
**ADR numbers reserved:** <from items or none>
**Next window:** <12:00 / 21:00 JST>
```

## Step 3 - Do not spawn workers

This skill only primes context. Write the Board before delegating
(`.claude/rules/board.md`), then spawn workers with the agent preamble.
Non-destructive: no edits, no commits.
