package com.astarworks.astera.core.domain.skillengine.hit

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * Narrow phase of hit detection. The caller has already gathered [HitCandidate]s with a nearby query
 * around the volume's broad sphere; this applies one simple shape test per candidate.
 */
public class DefaultContactResolver : ContactResolver {
    override fun narrow(
        hitId: String,
        tick: Int,
        hitterFrame: String,
        volume: ResolvedVolume,
        hitterVelocity: Vec3,
        candidates: List<HitCandidate>,
    ): List<ContactEvent> {
        val out = ArrayList<ContactEvent>(candidates.size)
        for (c in candidates) {
            val touch = VolumeGeometry.touch(volume, c.box) ?: continue
            val normal = ContactNormals.normal(touch.point, c.box, hitterVelocity)
            out += ContactEvent(
                hitId = hitId,
                tick = tick,
                victim = c.entityId,
                hitterFrame = hitterFrame,
                part = null,
                pointWorld = touch.point,
                normalWorld = normal,
                pointLocal = ContactNormals.pointToLocal(touch.point, c.feet, c.yawDeg),
                normalLocal = ContactNormals.directionToLocal(normal, c.yawDeg),
                relVelocity = hitterVelocity - c.velocity,
            )
        }
        return out
    }
}
