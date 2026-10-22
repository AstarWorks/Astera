package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.service.ProjectileContact
import com.astarworks.astera.combat.application.service.ProjectileFlight
import com.astarworks.astera.combat.application.service.ProjectileStepper
import com.astarworks.astera.combat.application.service.ProjectileWorldProbe
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Vector

/**
 * Paper's [ProjectileWorldProbe]: ray-traces the segment a projectile sweeps in
 * one tick against solid blocks (`World#rayTraceBlocks`, passable blocks such as
 * grass/water ignored) and against entity hitboxes (`World#rayTraceEntities`,
 * inflated by the projectile's hit radius). The nearer hit wins.
 *
 * Valid targets match the old vanilla-carrier listener: players (not the owner,
 * not spectators, not teammates — the damage layer blocks friendly fire anyway, so
 * passing through keeps an ally from soaking a shot) and non-invulnerable
 * living mobs; armor stands and display entities are ignored.
 *
 * The struck entity's id is its uuid ([PlayerId]); the impact resolver routes
 * players and mobs through `ApplyDamageUseCase` / `IEntityDamageGateway`.
 */
internal class PaperProjectileProbe(
    private val server: Server,
    private val teamLookup: () -> IMatchTeamLookup?,
    private val worldOf: (ProjectileFlight) -> World?,
) : ProjectileWorldProbe {

    override fun firstContact(flight: ProjectileFlight, from: Vec3, to: Vec3): ProjectileContact? {
        val world = worldOf(flight) ?: return null
        val delta = to - from
        val distance = delta.length()
        if (distance < MIN_SEGMENT) return null
        val dir = Vector(delta.x / distance, delta.y / distance, delta.z / distance)
        val start = Location(world, from.x, from.y, from.z)

        // Falling out of the world or into an unloaded chunk ends the flight at the segment start.
        if (to.y < world.minHeight - VOID_MARGIN) return ProjectileContact.Block(from)
        if (!world.isChunkLoaded(floorDiv16(to.x), floorDiv16(to.z))) return ProjectileContact.Block(from)

        val block = world.rayTraceBlocks(start, dir, distance, FluidCollisionMode.NEVER, true)
        val blockDistance = block?.hitPosition?.let { hit -> Vec3(hit.x, hit.y, hit.z).distance(from) }

        val owner = flight.owner
        val ownerTeam = teamLookup()?.teamOf(owner)
        val radius = ProjectileStepper.radiusOf(flight.spec.hit)
        val maxEntityDistance = blockDistance ?: distance
        val entity = world.rayTraceEntities(start, dir, maxEntityDistance, radius) { candidate ->
            isValidTarget(candidate, owner, ownerTeam)
        }
        val entityHit = entity?.hitEntity
        val entityPos = entity?.hitPosition

        return when {
            entityHit != null && entityPos != null ->
                ProjectileContact.Entity(PlayerId(entityHit.uniqueId), Vec3(entityPos.x, entityPos.y, entityPos.z))
            block != null -> block.hitPosition.let { ProjectileContact.Block(Vec3(it.x, it.y, it.z)) }
            else -> null
        }
    }

    override fun homingTarget(flight: ProjectileFlight, radius: Double): Vec3? {
        val world = worldOf(flight) ?: return null
        val center = Location(world, flight.position.x, flight.position.y, flight.position.z)
        val ownerTeam = teamLookup()?.teamOf(flight.owner)
        return world.getNearbyEntities(center, radius, radius, radius) { isValidTarget(it, flight.owner, ownerTeam) }
            .minByOrNull { it.location.distanceSquared(center) }
            ?.let { e ->
                val chest = e.location.add(0.0, e.height / 2.0, 0.0)
                Vec3(chest.x, chest.y, chest.z)
            }
    }

    private fun isValidTarget(
        entity: Entity,
        owner: PlayerId,
        ownerTeam: com.astarworks.astera.core.domain.match.TeamId?,
    ): Boolean {
        if (entity.uniqueId == owner.uuid || entity.isDead) return false
        return when (entity) {
            is Player -> {
                if (entity.gameMode == GameMode.SPECTATOR) return false
                val team = teamLookup()?.teamOf(PlayerId(entity.uniqueId))
                !(ownerTeam != null && team == ownerTeam)
            }
            is ArmorStand -> false
            is LivingEntity -> !entity.isInvulnerable
            else -> false
        }
    }

    private fun floorDiv16(v: Double): Int = Math.floorDiv(Math.floor(v).toInt(), 16)

    private companion object {
        const val MIN_SEGMENT: Double = 1.0e-6
        const val VOID_MARGIN: Int = 32
    }
}
