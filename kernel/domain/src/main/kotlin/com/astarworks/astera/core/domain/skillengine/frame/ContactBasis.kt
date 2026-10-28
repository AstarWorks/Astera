package com.astarworks.astera.core.domain.skillengine.frame

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Basis
import com.astarworks.astera.core.domain.geometry.math.Frame

/**
 * The `contact` frame (ADR-0303 R23): origin = the contact point, up = the contact normal (an entity hit has no surface, so its
 * normal is world up), forward = the caster's yaw forward made orthogonal to up. When the caster looks straight along the normal
 * the forward falls back to the stable alternate of [Basis.facing], never to an undefined axis.
 */
public object ContactBasis {
    private val WORLD_UP = Vec3(0.0, 1.0, 0.0)

    /** [normal] null = an entity hit (world up); [casterForward] is the caster's forward, its vertical part is dropped first. */
    public fun frame(point: Vec3, normal: Vec3?, casterForward: Vec3): Frame {
        val up = normal?.takeIf { it.lengthSquared() > 1e-12 }?.normalize() ?: WORLD_UP
        val level = Vec3(casterForward.x, 0.0, casterForward.z)
        val ref = if (level.lengthSquared() > 1e-12) level else casterForward
        val forward = ref - up * ref.dot(up)
        return Frame(point, Basis.facing(forward, up))
    }
}
