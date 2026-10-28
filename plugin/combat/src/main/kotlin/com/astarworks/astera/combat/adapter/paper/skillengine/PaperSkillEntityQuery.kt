package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.application.port.outbound.IStructureTargets
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockHit
import com.astarworks.astera.core.application.skillengine.runtime.port.EntityRoot
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.UUID

/**
 * [ISkillEntityQuery]: a plain nearby-entities query returning hitbox AABBs for the broad phase.
 * Hittable = living, valid, not an armor stand / spectator / invulnerable mob (same rule as
 * `PaperNearbyTargetQuery`). Main thread only.
 *
 * One instance serves the world [worldOf] names at call time (the cast being played). A world that is not loaded
 * is an error, never an empty answer.
 */
public class PaperSkillEntityQuery(
    private val server: Server,
    /** The world of the cast being played (the platform tracks it; the v3 ports carry no cast context yet). */
    private val worldOf: () -> WorldId,
    /** Lazy: Match registers the structure targets (Annihilation pillars) after Combat wires. */
    private val structures: () -> IStructureTargets? = { null },
) : ISkillEntityQuery {

    private val worldId: WorldId get() = worldOf()

    private val world: World
        get() = server.getWorld(worldId.value) ?: error("skill engine: world '${worldId.value}' of the cast is not loaded")

    override fun nearby(point: Vec3, radius: Double, excluding: UUID?): List<HitCandidate> {
        if (radius <= 0.0) return emptyList()
        val world = world
        val origin = Location(world, point.x, point.y, point.z)
        val out = ArrayList<HitCandidate>()
        for (e in world.getNearbyLivingEntities(origin, radius)) {
            if (e.uniqueId == excluding || !hittable(e)) continue
            val bb = e.boundingBox
            val loc = e.location
            out += HitCandidate(
                entityId = e.uniqueId,
                feet = Vec3(loc.x, loc.y, loc.z),
                yawDeg = loc.yaw.toDouble(),
                box = HitBox(Vec3(bb.minX, bb.minY, bb.minZ), Vec3(bb.maxX, bb.maxY, bb.maxZ)),
                velocity = e.velocity.let { Vec3(it.x, it.y, it.z) },
            )
        }
        // WP10: standing, damageable structures are hit targets too; they travel under their synthetic combat id and
        // take damage through the entity damage gateway (the v1 projectile resolver did the same).
        structures()?.findInRadius(point, radius + STRUCTURE_REACH, worldId)?.forEach { s ->
            val c = s.position
            out += HitCandidate(
                entityId = s.id.uuid,
                feet = Vec3(c.x, c.y - 0.5, c.z),
                yawDeg = 0.0,
                box = HitBox(Vec3(c.x - 0.5, c.y - 0.5, c.z - 0.5), Vec3(c.x + 0.5, c.y + 0.5, c.z + 0.5)),
                velocity = Vec3.ZERO,
            )
        }
        return out
    }

    override fun rootOf(entity: UUID): EntityRoot? {
        val e = server.getEntity(entity) as? LivingEntity ?: return null
        val loc = e.location
        return EntityRoot(
            feet = Vec3(loc.x, loc.y, loc.z),
            yawDeg = loc.yaw.toDouble(),
            pitchDeg = loc.pitch.toDouble(),
            eyeHeight = e.eyeHeight,
            height = e.height,
        )
    }

    override fun healthRatio(entity: UUID): Double {
        val e = server.getEntity(entity) ?: error("hp_ratio: entity $entity is not loaded")
        val living = e as? LivingEntity ?: error("hp_ratio: entity $entity is not a living entity")
        val max = living.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value
            ?: error("hp_ratio: entity $entity has no max health")
        check(max > 0.0) { "hp_ratio: entity $entity has non-positive max health" }
        return (living.health / max).coerceIn(0.0, 1.0)
    }

    override fun raycastBlock(from: Vec3, to: Vec3): BlockHit? {
        val world = world
        val delta = to - from
        val dist = delta.length()
        if (dist < 1.0e-9) return null
        val dir = delta * (1.0 / dist)
        val hit = world.rayTraceBlocks(
            Location(world, from.x, from.y, from.z),
            Vector(dir.x, dir.y, dir.z),
            dist,
            FluidCollisionMode.NEVER,
            true,
        ) ?: return null
        val block = hit.hitBlock ?: return null
        val face = hit.hitBlockFace?.direction ?: Vector(0.0, 0.0, 0.0)
        return BlockHit(
            point = Vec3(hit.hitPosition.x, hit.hitPosition.y, hit.hitPosition.z),
            normal = Vec3(face.x, face.y, face.z),
            blockKey = block.type.key.toString(),
        )
    }

    private companion object {
        /** The structure query measures to the block centre; the box reaches half a block (plus the diagonal) further. */
        const val STRUCTURE_REACH: Double = 1.0
    }

    private fun hittable(e: LivingEntity): Boolean {
        if (e.isDead || !e.isValid) return false
        return when (e) {
            is Player -> e.gameMode != GameMode.SPECTATOR
            is ArmorStand -> false
            else -> !e.isInvulnerable
        }
    }
}
