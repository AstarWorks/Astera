package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.port.outbound.NearbyTarget
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player

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
 * Main thread only (reads live world state).
 */
public class PaperNearbyTargetQuery(
    private val server: Server,
) : INearbyTargetQuery {

    override fun findInRadius(center: Vec3, radius: Double, world: WorldId?): List<PlayerId> =
        findNearby(center, radius, world).map { it.id }

    override fun findViewers(center: Vec3, radius: Double, world: WorldId?): List<PlayerId> {
        if (radius <= 0.0) return emptyList()
        val worlds: List<World> = if (world != null) listOfNotNull(server.getWorld(world.value)) else server.worlds
        val radiusSq = radius * radius
        val out = ArrayList<PlayerId>()
        for (w in worlds) {
            for (p in w.players) {
                if (p.isDead || !p.isValid) continue
                val loc = p.location
                val dx = loc.x - center.x
                val dy = loc.y + p.height / 2.0 - center.y
                val dz = loc.z - center.z
                if (dx * dx + dy * dy + dz * dz <= radiusSq) out += PlayerId(p.uniqueId)
            }
        }
        return out
    }

    override fun findNearby(center: Vec3, radius: Double, world: WorldId?): List<NearbyTarget> {
        if (radius <= 0.0) return emptyList()
        val worlds: List<World> = if (world != null) {
            listOfNotNull(server.getWorld(world.value))
        } else {
            server.worlds
        }
        val out = ArrayList<NearbyTarget>()
        val radiusSq = radius * radius
        for (w in worlds) {
            val origin = Location(w, center.x, center.y, center.z)
            for (entity in w.getNearbyLivingEntities(origin, radius)) {
                if (!isDamageable(entity)) continue
                val loc = entity.location
                val dx = loc.x - center.x
                val dy = loc.y + entity.height / 2.0 - center.y
                val dz = loc.z - center.z
                if (dx * dx + dy * dy + dz * dz > radiusSq) continue
                out += NearbyTarget(
                    id = PlayerId(entity.uniqueId),
                    position = Vec3(loc.x, loc.y, loc.z),
                    height = entity.height,
                )
            }
        }
        return out
    }

    private fun isDamageable(entity: LivingEntity): Boolean {
        if (entity.isDead || !entity.isValid) return false
        return when (entity) {
            is Player -> entity.gameMode != org.bukkit.GameMode.SPECTATOR
            is ArmorStand -> false
            else -> !entity.isInvulnerable
        }
    }
}
