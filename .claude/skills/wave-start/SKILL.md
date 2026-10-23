---
name: wave-start
description: Bootstrap a Wave implementation session by reading the four canonical docs in the right order and summarizing scope, worker territory, off-limits paths, and ADR allocation. Fires when the user says "Wave Nx を実装して", "Wave Nx start", "Wave Nx (xxx) を始めて", or invokes /wave-start. Does not spawn workers — primes orchestrator context only.
allowed-tools: Read, Bash, Glob, Grep
---

# wave-start — Wave session orchestrator priming

The user just triggered a wave implementation. **Read the four docs in this
exact order**, then produce a one-screen summary so the orchestrator (you)
has the right context before Phase A starts.

## Step 0 — Be in the wave's own worktree

Every track runs in its own worktree (`.claude/rules/wave-execution.md
§"Every track → its own worktree"`); no confirmation needed. Slug =
`wave-{N}{x}` (one worktree per wave if several are triggered at once).

- If `/IdeaProjects/Astera/.claude/state/handoffs/wave-{N}{x}.md` exists,
  read it first and resume from it.
- If the cwd is already `/IdeaProjects/Astera/.claude/worktrees/wave-{N}{x}`,
  continue. Otherwise create it and work from there:

```
git -C /IdeaProjects/Astera fetch origin
git -C /IdeaProjects/Astera worktree add .claude/worktrees/wave-{N}{x} -b wp/wave-{N}{x} origin/main
cd /IdeaProjects/Astera/.claude/worktrees/wave-{N}{x}
```

Never run the wave in the primary checkout `/IdeaProjects/Astera`.

## Step 1 — Read in this order

1. `/home/node/.claude/plans/home-node-claude-plans-radius-roadmap-handoff.md`
   — session entry point, every wave's trigger phrase + handoff doc path.
   Confirm the triggered wave is listed.
2. `/home/node/.claude/plans/wave-{N}{x}-handoff.md` (extract `N` and `x`
   from the user prompt; e.g., "Wave 5b" → `wave-5b-handoff.md`). This is
   the full Phase A/B/C plan + worker territory.
3. `docs/IMPLEMENTATION-ROADMAP.md` — find the row for this wave, note any
   cross-wave dependencies and the ADR number from
   §"ADR allocation per wave".
4. `docs/RTM-PARITY-TRACKER.md` — find the rows assigned to this wave; these
   are the features `rtm-sync` will flip at Phase C close.

If the handoff doc (step 2) does not exist, **stop** and ask the user
whether they want to draft one before proceeding.

## Step 2 — Cross-check against the rules

Read these to refresh constraints (skim, not memorize):

- `.claude/rules/wave-execution.md` — Worker禁則 + off-limits paths
- `.claude/rules/module-layout.md` — which plugins this wave touches and
  the sibling-isolation matrix
- `.claude/CLAUDE.md` — the 8 non-negotiable rules

## Step 3 — Summary report

Output in this exact shape (markdown, single message, ≤ 1 screen):

```
## Wave Nx — bootstrap summary

**Wave:** <wave id> — <one-line goal from the handoff doc>
**Handoff doc:** <path>
**ADR number reserved:** ADR-NNNN (from IMPLEMENTATION-ROADMAP §"ADR allocation")
**Plugins touched:** <list, e.g. plugin:combat, plugin:stats>
**Phase A (orchestrator solo):** <bullets from handoff>
**Phase B workers:** <count> — <one-line territory per worker>
**Phase C (orchestrator solo):** <bullets from handoff>

**RTM rows to flip at Phase C:** <list of feature row IDs from RTM tracker>

**Off-limits this wave (in addition to the global list):** <handoff-specific or "none">

**Cross-wave dependencies (must already be ✅):** <list or "none">

## Ready

Phase A starts. Proposed TaskCreate items:
1. <Phase A item>
2. <Phase A item>
...
```

## Step 4 — Do NOT spawn workers

This skill **only primes context**. Phase B worker spawn happens after the
orchestrator has done Phase A (mkdir, port skeletons, dep prep). The
orchestrator decides when to call `Agent` for each worker, using the
territory table from the handoff doc.

## Notes

- If the user's prompt mentions a wave letter that isn't in the roadmap
  (e.g., "Wave 99z"), stop and ask the user which wave they actually meant.
- If the wave is already marked `✅` in `RTM-PARITY-TRACKER.md`, point that
  out and ask whether this is a re-run or a follow-up wave.
- This skill is **non-destructive**. No edits, no commits.
