package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.port.outbound.NearbyTarget
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.entity.LivingEntity

/**
 * Paper [INearbyTargetQuery] (WP3): damageable living things around a point —
 * online non-dead players plus non-player [LivingEntity] mobs. Mobs are
 * returned as `PlayerId(entityUuid)`; `ApplyDamageUseCase` routes them to the
 * entity damage path.
 *
 * Mobs that must never be hit are skipped: armor stands, invulnerable entities
 * (lobby NPCs, dummies marked invulnerable) and dead / invalid ones. Spectators
 * are skipped like Bukkit's own targeting does.
 *
 * Owner-thread refresh for local targets; immutable snapshots for foreign regions.
 */
public class PaperNearbyTargetQuery(
    private val grid: CombatSpatialGrid,
) : INearbyTargetQuery {

    override fun findInRadius(center: Vec3, radius: Double, world: WorldId?): List<PlayerId> =
        findNearby(center, radius, world).map { it.id }

    override fun findNearby(center: Vec3, radius: Double, world: WorldId?): List<NearbyTarget> {
        if (radius <= 0.0) return emptyList()
        val extent = Vec3(radius, radius, radius)
        val out = ArrayList<NearbyTarget>()
        val radiusSq = radius * radius
        for (snapshot in grid.candidates(world, center - extent, center + extent)) {
            val current = grid.refreshLocal(snapshot)
            if (!current.damageable) continue
            val loc = current.feet
            val dx = loc.x - center.x
            val dy = loc.y + current.height / 2.0 - center.y
            val dz = loc.z - center.z
            if (dx * dx + dy * dy + dz * dz > radiusSq) continue
            out += current.nearby()
        }
        return out
    }

}
