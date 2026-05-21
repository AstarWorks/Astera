package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.replay.Vec3Snapshot

/**
 * Outbound port: best-effort per-tick player-state lookup for replay
 * key-frame snapshotting (Wave 5v, ADR-0088).
 *
 * Designed to be impl-able from either Bukkit (Wave 6a:
 * `PaperRecorderStateProvider` reads `player.location` / `player.health`
 * / `player.inventory.itemInMainHand` directly) **or** from in-memory
 * caches that other waves build (`IPlayerLocationQueryService` from Wave
 * 5n, `IPlayerHealthGateway` from Wave 5k). Phase 1 PoC ships a
 * `NoopRecorderStateProvider` (returns null for everything) so the
 * recorder produces empty key frames in environments where neither Paper
 * nor Wave 5n/5k caches are present.
 *
 * **Null semantics**: every method returns null for unknown players,
 * which the recorder treats as "skip this entry in the key-frame map"
 * (not "default value"). A key frame for an offline-only match correctly
 * carries empty maps rather than zero-filled placeholders.
 *
 * Per-match scoping: callers pass `matchId` so the impl can restrict
 * queries to the players in that match (e.g. ignore spectators whose
 * positions outside the arena are not relevant to playback).
 */
public interface IRecorderStateProvider {

    /** Player IDs whose state should appear in [matchId]'s next key frame. */
    public fun participantsOf(matchId: MatchId): Collection<PlayerId>

    /** Current position, or null if unknown. */
    public fun positionOf(playerId: PlayerId): Vec3Snapshot?

    /** Current health, or null if unknown. */
    public fun healthOf(playerId: PlayerId): Double?

    /** Currently equipped main-hand item slug, or null if unknown / empty hand. */
    public fun activeItemOf(playerId: PlayerId): ItemId?
}
