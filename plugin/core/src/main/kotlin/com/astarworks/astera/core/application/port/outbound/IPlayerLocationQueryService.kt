package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: vendor-neutral player-position spatial query service.
 *
 * Wave 5n (ADR-0080) — the proximity primitive underpinning:
 * - Wave 5a proximity-based skill (AOE / blast radius / aura).
 * - Wave 5c anomaly spawn (e.g. "spawn entity at the location of a random
 *   nearby player").
 * - Wave 5o spawn protection (count players inside a safe-zone region).
 *
 * Distinguished from the existing player handles:
 *
 * - [com.astarworks.astera.core.adapter.api.entity.IMcPlayer.location] —
 *   read-only single-player position (vendor-neutral handle wrapper).
 * - [IPlayerGateway.findByName] — name-based lookup, no spatial query.
 *
 * Neither of those scale to "give me every player inside this region" or
 * "is anyone within range?" without an O(n) walk over every online player.
 * This port adds the bookkeeping (a [com.astarworks.astera.core.domain.geometry.spatial.SpatialIndex]
 * backed by an [com.astarworks.astera.core.domain.geometry.spatial.AabbTree])
 * so callers get O(log n) average lookup.
 *
 * **State updates**: implementations are expected to subscribe to the
 * underlying player-move event stream (Bukkit `PlayerMoveEvent` in the
 * Paper impl) and update the index at most once per second per player, so
 * the index lags reality by a few ticks. Callers needing exact frame-
 * accurate position should hold an `IMcPlayer` reference and read
 * `IMcPlayer.location`.
 */
public interface IPlayerLocationQueryService {

    /**
     * Returns every online player currently inside [region]. Empty if no
     * player matches.
     */
    public fun playersIn(world: WorldId, region: Region): Collection<PlayerId>

    /**
     * Returns every online player in [world] whose last-known position
     * lies within [radius] blocks of [center] (Euclidean distance).
     */
    public fun playersWithinDistance(world: WorldId, center: Vec3, radius: Double): Collection<PlayerId>

    /**
     * Returns the player closest to [center] in [world] whose distance is
     * at most [maxDistance]. Returns `null` when no player is within range.
     */
    public fun nearestPlayer(world: WorldId, center: Vec3, maxDistance: Double): PlayerId?

    /**
     * Update the last-known position of [playerId] in [world]. Called by
     * the Paper adapter on `PlayerMoveEvent` and on join.
     */
    public fun updatePlayerPosition(playerId: PlayerId, world: WorldId, position: Vec3)

    /**
     * Remove [playerId] from the index. Called on quit / world change to
     * the source world.
     */
    public fun forgetPlayer(playerId: PlayerId)
}
