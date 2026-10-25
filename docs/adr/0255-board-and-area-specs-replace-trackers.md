---
status: Accepted
date: 2026-10-26
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0255: Board + area specs replace trackers

## Status

Accepted (founder approval 2026-10-26). Retires the practice, not the content, of: `docs/RTM-PARITY-TRACKER.md`,
`docs/IMPLEMENTATION-ROADMAP.md`, `docs/roadmap.md`, `docs/CONTENT-ROADMAP.md`,
`docs/plans/2026-10-core-vision-backlog.md`. Older ADRs that link to those files stay immutable; the files remain in
git history.

## Context

Truth about "what is live and what is next" was spread over chat, five handoffs, ~250 ADRs, an 831-line RTM tracker
that stopped at Wave 6-7, a roadmap with 2026-05 numbers, and a work-package backlog without done-ness. Founder
feedback lived only in handoff "Live decisions" and got buried (four unanswered questions in one handoff). The e2e
lock (one run, ~12 min) and shared hot files (`norma.yaml`, `content/languages/*.yaml`, `Astera*Plugin.kt`) made
parallel sessions collide. About 830 commits and 179 worktrees in a week made "what is live" invisible.

## Decision

1. **The Board is the single source for items.** A claude.ai Artifact with a shared database
   (https://claude.ai/artifact/2D2a8S41RpDwApdZFnPno3): collections `items` and `memos`, lanes
   `idea -> ready -> in-progress -> merged -> live -> founder-check -> verified` plus `blocked` / `dropped`.
   The manager is the only writer; agents report a 「Board 更新案」. The manager writes founder feedback to the Board
   before delegating. GitHub Issues/Projects are not used. Rules: `.claude/rules/board.md`.
2. **Area specs hold the living picture.** `docs/vision/VISION.md` and `docs/vision/areas/<area>.md`
   (構想 / 現状 / 理想形 / 未決 / governing ADRs). ADRs stay the immutable "why".
   The 現状 section is updated by the agent that completes an item (`area-spec-sync` for bulk edits).
3. **Tracks by area with a file-ownership table and hot files** (`.claude/rules/tracks.md`); an agent needing a file
   it does not own writes a 「依頼」 line instead of editing.
4. **Integration windows.** Two per day (12:00 / 21:00 JST); tracks and agents never run e2e, they add the test that
   proves their item; the manager merges, runs ONE batched e2e, pushes, then sends one batch report. This
   supersedes the per-agent e2e permission of 2026-10-23.
5. **Deleted:** the five files above (facts: RTM catalog in ADR-0029; remaining work on the Board; Phase 4+ scope in
   `VISION.md` "Out of scope"). The `rtm-sync` agent becomes `area-spec-sync`; the Wave read-order and the `wave-start`
   skill now read VISION -> area spec -> Board.

## Consequences

- One place answers "what is live / merged / in progress / needs the founder".
- Positive: fewer merge conflicts, one e2e per window. Negative: the Board depends on the manager discipline
  (write before delegating) and on the artifact service; a periodic snapshot (`docs/board/SNAPSHOT.md`) preserves history.
- Product decisions recorded in `VISION.md` on this date: Norma is part of every mode; siege-scale maps with one
  small debug arena; gathering/crafting/fishing frozen; sound sourced free only (ADR-0026's paid route is no longer
  followed; a formal supersede ADR is still owed); starter kit includes a gun with the 2-weapon cap kept.
- Code comments in `plugin/match` and `plugin/core` that name the removed tracker are stale text only.
