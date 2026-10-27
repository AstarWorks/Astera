package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.port.BlockHit
import com.astarworks.astera.core.application.skillengine.runtime.port.EntityRoot
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.domain.geometry.Vec3
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
 * The port carries no world id: the world is that of [excluding] (the caster) when given, otherwise
 * the world last seen through this query, otherwise the first world that has players.
 */
public class PaperSkillEntityQuery(private val server: Server) : ISkillEntityQuery {

    private var lastWorld: World? = null

    override fun nearby(point: Vec3, radius: Double, excluding: UUID?): List<HitCandidate> {
        if (radius <= 0.0) return emptyList()
        val world = worldOf(excluding) ?: return emptyList()
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
        return out
    }

    override fun rootOf(entity: UUID): EntityRoot? {
        val e = server.getEntity(entity) as? LivingEntity ?: return null
        lastWorld = e.world
        val loc = e.location
        return EntityRoot(
            feet = Vec3(loc.x, loc.y, loc.z),
            yawDeg = loc.yaw.toDouble(),
            pitchDeg = loc.pitch.toDouble(),
            eyeHeight = e.eyeHeight,
            height = e.height,
        )
    }

    override fun raycastBlock(from: Vec3, to: Vec3): BlockHit? {
        val world = worldOf(null) ?: return null
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

    /** The world of the most recent cast owner (the "last seen world" the world-less skill ports share). */
    public fun activeWorld(): World? = worldOf(null)

    /** A cast started in [world]: the world-less particle and sound ports follow it. */
    public fun noteWorld(world: World) {
        lastWorld = world
    }

    private fun worldOf(excluding: UUID?): World? {
        val fromEntity = excluding?.let { server.getEntity(it)?.world }
        if (fromEntity != null) lastWorld = fromEntity
        return fromEntity ?: lastWorld ?: server.worlds.firstOrNull { it.players.isNotEmpty() } ?: server.worlds.firstOrNull()
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
