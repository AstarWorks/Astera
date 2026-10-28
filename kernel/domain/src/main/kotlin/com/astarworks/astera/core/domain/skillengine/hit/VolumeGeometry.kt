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
        is ResolvedVolume.Box -> orientedBox(volume, box)
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

    /**
     * Oriented box vs axis-aligned hitbox: separating-axis test over the 15 candidate axes (3 + 3 face normals, 9 edge
     * cross products). The contact point is the hitbox point closest to the oriented box's centre (the centre itself when
     * it lies inside the hitbox).
     */
    private fun orientedBox(v: ResolvedVolume.Box, box: HitBox): VolumeTouch? {
        val axesA = arrayOf(
            org.joml.Vector3d(1.0, 0.0, 0.0).rotate(v.rotation),
            org.joml.Vector3d(0.0, 1.0, 0.0).rotate(v.rotation),
            org.joml.Vector3d(0.0, 0.0, 1.0).rotate(v.rotation),
        )
        val halfA = doubleArrayOf(abs(v.halfExtents.x), abs(v.halfExtents.y), abs(v.halfExtents.z))
        val axesB = arrayOf(
            org.joml.Vector3d(1.0, 0.0, 0.0), org.joml.Vector3d(0.0, 1.0, 0.0), org.joml.Vector3d(0.0, 0.0, 1.0),
        )
        val c = box.center
        val halfB = doubleArrayOf((box.max.x - box.min.x) / 2.0, (box.max.y - box.min.y) / 2.0, (box.max.z - box.min.z) / 2.0)
        val t = org.joml.Vector3d(c.x - v.center.x, c.y - v.center.y, c.z - v.center.z)
        val candidates = ArrayList<org.joml.Vector3d>(15)
        candidates += axesA
        candidates += axesB
        for (a in axesA) for (b in axesB) candidates += org.joml.Vector3d(a).cross(b)
        for (l in candidates) {
            val len = l.length()
            if (len < 1e-9) continue
            l.div(len)
            var ra = 0.0
            var rb = 0.0
            for (i in 0..2) {
                ra += halfA[i] * abs(axesA[i].dot(l))
                rb += halfB[i] * abs(axesB[i].dot(l))
            }
            if (abs(t.dot(l)) > ra + rb + EPS) return null
        }
        return VolumeTouch(clampToBox(v.center, box))
    }

    private fun distSqToBox(p: Vec3, box: HitBox): Double {
        val dx = max(max(box.min.x - p.x, 0.0), p.x - box.max.x)
        val dy = max(max(box.min.y - p.y, 0.0), p.y - box.max.y)
        val dz = max(max(box.min.z - p.z, 0.0), p.z - box.max.z)
        return dx * dx + dy * dy + dz * dz
    }

    private fun lerp(a: Vec3, b: Vec3, t: Double): Vec3 = a + (b - a) * min(1.0, max(0.0, t))
}
