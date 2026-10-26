package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: read-only "where is this player right now?" lookup
 * (Wave 5complete, ADR-0117).
 *
 * Phase 1 in-memory default
 * ([com.astarworks.astera.core.application.service.PlayerMovedLocationTracker])
 * subscribes to [com.astarworks.astera.core.domain.event.PlayerMoved] and
 * keeps a last-known `(world, position)` per player. Wave 6b's Paper adapter
 * overrides via `IMcServer.findPlayer(id).location` for source-of-truth
 * accuracy.
 *
 * Returns `null` when the player has never been seen this session
 * (offline at boot, never moved, or already quit + entry pruned).
 *
 * # Why this exists
 *
 * Domain events like [com.astarworks.astera.core.domain.event.LootDropped]
 * intentionally do not embed positions (replay-determinism argument: the
 * roll is a logical fact, not a spatial one). Listeners that need a
 * position (item-entity spawn, particle effect placement) resolve it
 * separately via this port.
 */
public data class PlayerLocation(
    public val world: WorldId,
    public val position: Vec3,
)

public interface IPlayerLocationLookup {

    /** Last-known location of [playerId], or `null` if untracked. */
    public fun locate(playerId: PlayerId): PlayerLocation?
}
