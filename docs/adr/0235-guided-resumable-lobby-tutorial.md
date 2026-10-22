---
status: Proposed
date: 2026-10-23
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0235: Guided, resumable lobby tutorial

## Status

Proposed — implementation awaits CI and a live playtest.

## Context

First join enrolled a tutorial whose send-message step had no consumer. The sequencer's
repository default was volatile, its player command could bypass waits, and its completion
signal did not reach the existing graduation reward listeners.

## Decision

Reuse `getting-started` and `WaitForAction` with ten ordered tokens: menu, range, rack,
primary, secondary, reload, HUD, shop, vault, queue. Successful operations emit tokens:
F opens the real menu, lobby movement enters the range, rack delivery succeeds, a sword
primary damages a dummy, the sword's sneak+RC skill starts its cooldown, a pistol reload
finishes, the shop is rendered, the vault window opens, and the queue pad is entered.
The HUD step allows four uninterrupted seconds of observation in the lobby. It does not
claim to detect a player's gaze. The sword teaches LC and sneak+RC; the pistol teaches RC
and binds reload to Q (other weapons retain their bindings).

`TutorialGuide` rebuilds titles/subtitles, repeated action-bar hints and a private TextDisplay
from the saved cursor. Targets reuse the training plan, NPC content and live pad layout.
Single-viewer TextDisplays render in that player's language. Role, world and match guards
apply to progress and presentation. Enrollment may be saved before the join teleport; it
cannot advance until the player reaches the lobby. Match entry, quit, skip and shutdown
remove markers. On return, the current step is presented again.

`/astera tutorial start [script-id]` defaults to the starter script; skip pauses without
losing progress, and status explains the current action. Remove the player-facing advance
command. Completed scripts can replay, carrying a graduation latch forward. The first
completion publishes `TutorialCompleted` to the existing 50 STAR / achievement listeners;
replays do not grant another reward.

Persist cursors, pause/completion flags and the graduation latch. Standalone Paper uses
atomic per-player local files. Persistence supplies the network-wide Postgres repository
through additive migration V65. Resolve the repository through the service registry per
operation so priority overlays remain authoritative. No Bukkit imports enter wiring.

## Consequences

### Positive

- Players practice real mechanics; elapsed time cannot substitute for actions except HUD observation.
- Reconnect, restart and skip preserve the cursor; match gameplay remains independent.
- Existing tutorial content, commands, lobby landmarks and reward listeners are reused.

### Negative / Trade-offs

- The curriculum intentionally names the shipped sword and pistol; changing those bindings requires updating hints and the sim scenario.
- Graduation delivery uses the existing synchronous event path, with the latch saved before publication. A process failure between those operations can lose the reward; this is at-most-once delivery, not a transactional outbox.
- Local files are server-local; network deployments require the Postgres overlay.

### Mitigations

- Unit checks cover ordering, pause/resume, replay and guards; game-sim drives the actual inputs and services. File reopen and Postgres round-trip checks cover persistence.
- A manual en/ja playtest verifies client presentation; CI performs all compilation and execution.

## Alternatives Considered

- **Timed slideshow:** cannot prove that a player learned an action.
- **Separate tutorial engine:** duplicates the sequencer and its command/repository boundaries.

## References

- [[adr/0093-custom-advancement-npc-dialog-tutorial-sequencer-yaml-registry]]
- [[adr/0180]] · [[adr/0200]] · [[adr/0211]] · [[adr/0232-hotbar-belongs-to-the-player]]
- [Playtest](../contributing/playtest.md)
