package com.astarworks.astera.core.domain.skillengine.hit

import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min

/** Result of a narrow-phase test: where on the hitbox the volume touches it. */
internal data class VolumeTouch(val point: Vec3)

/**
 * Narrow-phase shape tests against an axis-aligned hitbox. Pure and allocation-light; the broad phase
 * (entities within a radius of the volume centre) is done by the caller.
 */
internal object VolumeGeometry {
    private const val EPS = 1e-9

    fun touch(volume: ResolvedVolume, box: HitBox): VolumeTouch? = when (volume) {
        is ResolvedVolume.Sphere -> sphere(volume, box)
        is ResolvedVolume.Capsule -> capsule(volume, box)
        is ResolvedVolume.Sector -> sector(volume, box)
        is ResolvedVolume.Box -> error("W1b-02: oriented box vs hitbox narrow phase")
    }

    fun clampToBox(p: Vec3, box: HitBox): Vec3 = Vec3(
        p.x.coerceIn(box.min.x, box.max.x),
        p.y.coerceIn(box.min.y, box.max.y),
        p.z.coerceIn(box.min.z, box.max.z),
    )

    private fun sphere(v: ResolvedVolume.Sphere, box: HitBox): VolumeTouch? {
        val closest = clampToBox(v.center, box)
        return if (closest.distanceSquared(v.center) <= v.radius * v.radius + EPS) VolumeTouch(closest) else null
    }

    /**
     * Capsule vs box: the distance from a point on segment a-b to the box is convex in the segment
     * parameter, so a ternary search finds the closest approach. The contact point is the box point
     * closest to the axis (the axis point itself when the axis passes through the box).
     */
    private fun capsule(v: ResolvedVolume.Capsule, box: HitBox): VolumeTouch? {
        var lo = 0.0
        var hi = 1.0
        repeat(60) {
            val m1 = lo + (hi - lo) / 3.0
            val m2 = hi - (hi - lo) / 3.0
            if (distSqToBox(lerp(v.a, v.b, m1), box) <= distSqToBox(lerp(v.a, v.b, m2), box)) hi = m2 else lo = m1
        }
        val axisPoint = lerp(v.a, v.b, (lo + hi) / 2.0)
        val closest = clampToBox(axisPoint, box)
        return if (closest.distanceSquared(axisPoint) <= v.radius * v.radius + EPS) VolumeTouch(closest) else null
    }

    /**
     * Sector vs box. The sector is a horizontal wedge about `forward` (full opening `angleDeg`,
     * radius `range`) with total vertical extent `height` centred on the origin. As in v1's cone
     * selector (melee reach), a target counts when its BODY CENTRE (hitbox centre) is inside: the
     * hitbox width adds no reach, so `range` is the eye-to-body-centre distance.
     */
    private fun sector(v: ResolvedVolume.Sector, box: HitBox): VolumeTouch? {
        val fwd = v.forward.normalize()
        val up = fwd.cross(v.left.normalize()).normalize()
        val halfRad = Math.toRadians(v.angleDeg / 2.0)
        val centre = Vec3((box.min.x + box.max.x) / 2.0, (box.min.y + box.max.y) / 2.0, (box.min.z + box.max.z) / 2.0)
        return if (inSector(centre, v, fwd, up, halfRad)) VolumeTouch(centre) else null
    }

    private fun inSector(p: Vec3, v: ResolvedVolume.Sector, fwd: Vec3, up: Vec3, halfRad: Double): Boolean {
        val rel = p - v.origin
        val dy = rel.dot(up)
        if (abs(dy) > v.height / 2.0 + EPS) return false
        val flat = rel - up * dy
        val len = flat.length()
        if (rel.length() > v.range + EPS) return false
        if (len < EPS) return true
        val cos = (flat.dot(fwd) / len).coerceIn(-1.0, 1.0)
        return acos(cos) <= halfRad + 1e-9
    }

    private fun distSqToBox(p: Vec3, box: HitBox): Double {
        val dx = max(max(box.min.x - p.x, 0.0), p.x - box.max.x)
        val dy = max(max(box.min.y - p.y, 0.0), p.y - box.max.y)
        val dz = max(max(box.min.z - p.z, 0.0), p.z - box.max.z)
        return dx * dx + dy * dy + dz * dz
    }

    private fun lerp(a: Vec3, b: Vec3, t: Double): Vec3 = a + (b - a) * min(1.0, max(0.0, t))
}
