package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.service.ProjectileContact
import com.astarworks.astera.combat.application.service.ProjectileFlight
import com.astarworks.astera.combat.application.service.ProjectileStepper
import com.astarworks.astera.combat.application.service.ProjectileWorldProbe
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.util.Vector

/**
 * Paper's [ProjectileWorldProbe]: ray-traces the segment a projectile sweeps in
 * one tick against solid blocks (`World#rayTraceBlocks`, passable blocks such as
 * grass/water ignored) and against spatial-grid entity hitboxes (Bukkit box ray tests,
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
    private val grid: CombatSpatialGrid,
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
        val end = from + Vec3(dir.x, dir.y, dir.z) * maxEntityDistance
        val min = Vec3(minOf(from.x, end.x) - radius, minOf(from.y, end.y) - radius, minOf(from.z, end.z) - radius)
        val max = Vec3(maxOf(from.x, end.x) + radius, maxOf(from.y, end.y) + radius, maxOf(from.z, end.z) + radius)
        val entityHit = grid.candidates(com.astarworks.astera.core.domain.world.WorldId(world.name), min, max)
            .asSequence().map(grid::refreshLocal)
            .filter { it.damageable && it.id != owner && (ownerTeam == null || teamLookup()?.teamOf(it.id) != ownerTeam) }
            .mapNotNull { s ->
                org.bukkit.util.BoundingBox(s.min.x, s.min.y, s.min.z, s.max.x, s.max.y, s.max.z)
                    .expand(radius).rayTrace(start.toVector(), dir, maxEntityDistance)?.hitPosition?.let { s.id to it }
            }
            .minByOrNull { (_, hit) -> hit.distanceSquared(start.toVector()) }
        val entityPos = entityHit?.second

        return when {
            entityHit != null && entityPos != null ->
                ProjectileContact.Entity(entityHit.first, Vec3(entityPos.x, entityPos.y, entityPos.z))
            block != null -> block.hitPosition.let { ProjectileContact.Block(Vec3(it.x, it.y, it.z)) }
            else -> null
        }
    }

    override fun homingTarget(flight: ProjectileFlight, radius: Double): Vec3? {
        val world = worldOf(flight) ?: return null
        val ownerTeam = teamLookup()?.teamOf(flight.owner)
        val extent = Vec3(radius, radius, radius)
        return grid.candidates(com.astarworks.astera.core.domain.world.WorldId(world.name), flight.position - extent, flight.position + extent)
            .map(grid::refreshLocal)
            .filter { it.damageable && it.id != flight.owner && (ownerTeam == null || teamLookup()?.teamOf(it.id) != ownerTeam) }
            .minByOrNull { it.feet.distanceSquared(flight.position) }
            ?.let { it.feet + Vec3(0.0, it.height / 2.0, 0.0) }
    }

    private fun floorDiv16(v: Double): Int = Math.floorDiv(Math.floor(v).toInt(), 16)

    private companion object {
        const val MIN_SEGMENT: Double = 1.0e-6
        const val VOID_MARGIN: Int = 32
    }
}
