# ADR-0198: Inventory snapshot on match entry (addendum to ADR-0187)

Status: Accepted (F3-12). Addendum to ADR-0187 / WP12 per-match snapshot.

## Problem
`MatchInventorySnapshotListener` captured the members present at `MatchStarted`, but matches start with empty teams and
players enter afterwards (queue formation, `/astera match entry`, `MatchAssignmentReceiver`). Nobody was snapshotted.

## Decision
- `EntryGameUseCase` publishes `PlayerEnteredMatch(matchId, playerId)` right after the participant is saved and BEFORE the
  spawn teleport / any kit, so the snapshot sees the pre-match inventory. The listener captures + persists it
  (idempotent per match and player; `MatchStarted` still captures pre-populated teams).
- Restore stays on `MatchEnded` (live apply + re-persist). A disconnect during a match keeps the persisted entry snapshot
  (`isHeld` blocks the quit save); the next join restores it. No separate leave event: there is no leave-match path.
- Cross-server: the game server restores too. On a GAME-role server the "pre-match" inventory is what the hand-off
  delivered from the lobby, so restoring it at match end is a no-op relative to the lobby copy. The return transfer then
  captures it normally (not held anymore) and the lobby restores via the ADR-0187 token. While held, a transfer reuses the
  entry snapshot as the authoritative version. This avoids a lobby-only rule and behaves identically for the `ALL` role.
- Exclusions: lobby kit items (ADR-0185) are dropped by `PaperInventorySnapshotSerializer` (`MenuKitTag`) on both capture
  and restore, so they never enter a snapshot; `onRestored` re-applies the kit per membership. No training-range item
  exists in the code base yet; when one is added it must be filtered in the same serializer.
