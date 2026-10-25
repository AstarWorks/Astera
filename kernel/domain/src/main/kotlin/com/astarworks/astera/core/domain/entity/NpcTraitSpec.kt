package com.astarworks.astera.core.domain.entity

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * Vendor-neutral trait that can be attached to a spawned NPC.
 *
 * The Minimal core adapter ignores traits it doesn't understand (no-op);
 * the Citizens edge adapter delegates to Citizens' real trait system.
 * Adding a new trait variant here MUST be matched by a Citizens-side
 * implementation before that variant ships to production.
 */
public sealed interface NpcTraitSpec {

    /** Make the NPC look at the nearest player within [range] blocks. */
    public data class Lookable(public val range: Double = 6.0) : NpcTraitSpec {
        init {
            require(range > 0.0) { "Lookable.range must be positive: $range" }
        }
    }

    /** Pathfind toward [targetPos] (or wander if null) at [speed] blocks/tick. */
    public data class Pathing(
        public val targetPos: Vec3? = null,
        public val speed: Double = 1.0,
    ) : NpcTraitSpec {
        init {
            require(speed > 0.0) { "Pathing.speed must be positive: $speed" }
        }
    }

    /**
     * Attach an existing hologram (display entity) above the NPC, tracking
     * the NPC's position. The hologram is owned by the spawner and is NOT
     * automatically despawned when the NPC despawns — call
     * [com.astarworks.astera.core.application.port.outbound.entity.IEntitySpawner.despawn]
     * on the hologram handle separately.
     */
    public data class HologramAttachment(public val hologramHandle: HologramHandle) : NpcTraitSpec
}
