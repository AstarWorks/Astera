---
status: Accepted
date: 2026-10-23
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0244: Mid-match reconnect routing and enforced spawn protection

## Status

Accepted

## Context

Founder playtest findings of the core loop:

1. A player who dropped out of an ACTIVE match and logged in again landed in the lobby. Two causes: Velocity's `try`
   order puts every fresh login on the lobby backend, and the whole disconnect / reconnect machinery
   (`DisconnectDuringMatchListener`, `ReconnectDuringMatchListener`, ADR-0079 §6) was dormant, because it keys off
   `PlayerSession.currentMatch` and nothing ever sets that on match entry (the match repository is the source of truth
   for membership). The hand-off service additionally rewrote the session to `Loading` -> `Lobby` on every join.
2. The Norma sidebar showed `Red: 3` without the goal, and every `TeamVictory` ended with "Ended by objective
   completion".
3. Spawn protection was granted (death window, respawn) and revoked on attack, but no damage path consulted it: it
   protected nobody.

## Decision

- **Membership, not session claim, starts the reconnect window.** `DisconnectDuringMatchListener` also accepts a roster
  member of an ACTIVE match and marks the session `Disconnected` with `currentMatch`. `PlayerHandoffService` keeps that
  claim across its `Loading` window. Only backends that host matches run these listeners.
- **Resume after the inventory restore**, on `PlayerArrivedOnServer`: team spawn (not the lobby-side spectator stub),
  spawn protection, loaner kit re-armed, match sidebar redrawn. ACTIVE and SUDDEN_DEATH are resumable; a dead claim
  becomes a plain lobby session.
- **Lobby -> game routing over a new proxy channel `astera.match.reconnect`.** The game server sends the lobby a `Hold`
  (seconds left of the window) when a player drops and a `Release` when they are back / the match ended. When a held
  player arrives on the lobby, `MatchReconnectRouter` transfers them to the game server. No Velocity plugin needed.
- **Norma**: `NormaGameMode.hudStatus` shows the kill goal and `score/goal` per team; `EndReason.NormaReached` and
  `NormaTimeLimit` carry the real reason (en + ja strings name the team).
- **Spawn protection is enforced** in `ApplyDamageUseCase` (every source except the void).

## Consequences

### Positive
- A dropped player rejoins their team through Velocity; the rest of the loop (rewards, return) is unchanged.
- Spawn protection actually protects after a respawn and a reconnect.

### Negative / Trade-offs
- Holds live in the lobby's memory: a lobby restart inside the window loses them (the player lands in the lobby and
  the 3-minute timer on the game server still removes them).
- Removal after the window still only resets the session; the roster keeps the name (pre-existing).
- Rejoining restores the pre-match inventory snapshot, not items picked up during the match.
- No spawn protection at match start (many scenarios hit players right after `ACTIVE`).

## Alternatives Considered

- **Velocity plugin that routes by match membership**: new deploy artifact and a second source of truth.
- **Lobby reads the shared session / match store**: only works when persistence is wired on both backends; the message
  hold works on every topology and is covered by the simulator.
- **Set `currentMatch` on entry**: touches every entry path and the lobby sidebar's source-of-truth rule.

## References

- ADR-0079 §6 (reconnect window), ADR-0179 (lobby / game split), ADR-0187 (hand-off), ADR-0206 (team colours)
- `tools/game-sim/.../MidMatchReconnectScenarioTest.kt`, `tools/e2e/.../ReconnectE2eTest.kt`
