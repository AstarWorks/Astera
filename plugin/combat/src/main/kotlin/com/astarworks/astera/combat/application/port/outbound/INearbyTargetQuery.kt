package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound: spatial query for nearby players within a radius.
 *
 * Wave 5a `SkillCastDispatcher` uses this for MELEE and AOE archetypes to
 * find candidate damage targets. Wave 6 supplies `PaperNearbyTargetQuery`
 * (Bukkit `World.getNearbyEntities`); Wave 5a default is
 * `EmptyNearbyTargetQuery` (returns empty list — pure domain testable).
 */
public interface INearbyTargetQuery {
    public fun findInRadius(center: Vec3, radius: Double, world: WorldId? = null): List<PlayerId>
}
