package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound: spatial query for nearby damage targets within a radius.
 *
 * Targets are players **and non-player living entities** (mobs). Mobs are
 * addressed as `PlayerId(entityUuid)` (see [IEntityDamageGateway]). The Paper
 * impl is `PaperNearbyTargetQuery`; the default is `EmptyNearbyTargetQuery`.
 */
public interface INearbyTargetQuery {
    public fun findInRadius(center: Vec3, radius: Double, world: WorldId? = null): List<PlayerId>

    /**
     * Players who can SEE an effect around a point (screen overlays, other client-only feedback):
     * online players only, spectators included (unlike damage targets, which skip them).
     */
    public fun findViewers(center: Vec3, radius: Double, world: WorldId? = null): List<PlayerId> =
        findInRadius(center, radius, world)

    /**
     * Like [findInRadius] but with positions. The default derives ids from
     * [findInRadius] with unknown position (callers then fall back to a pose
     * lookup); hosts that know positions override this.
     */
    public fun findNearby(center: Vec3, radius: Double, world: WorldId? = null): List<NearbyTarget> =
        findInRadius(center, radius, world).map { NearbyTarget(it, position = null) }
}

/**
 * A candidate target. [position] is the **feet** position when the host knows
 * it, [height] the hitbox height used to aim at the body centre.
 */
public data class NearbyTarget(
    val id: PlayerId,
    val position: Vec3?,
    val height: Double = DEFAULT_HEIGHT,
) {
    public companion object {
        /** Standing player hitbox height. */
        public const val DEFAULT_HEIGHT: Double = 1.8
    }
}
