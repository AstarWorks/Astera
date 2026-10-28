package com.astarworks.astera.core.domain.skillengine.hit

import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion

/** Contact normal (F-4) and victim-frame conversion (F-5). */
internal object ContactNormals {
    private const val DEGENERATE_SQ = 1e-12
    private val UP = Vec3(0.0, 1.0, 0.0)

    /**
     * normalize(point - closest point on the victim's vertical centre axis). When the point lies on
     * the axis the normal is -(hitter velocity); with no velocity either, world up.
     */
    fun normal(point: Vec3, box: HitBox, hitterVelocity: Vec3): Vec3 {
        val c = box.center
        val onAxis = Vec3(c.x, point.y.coerceIn(box.min.y, box.max.y), c.z)
        val d = point - onAxis
        if (d.lengthSquared() > DEGENERATE_SQ) return d.normalize()
        val back = hitterVelocity * -1.0
        return if (back.lengthSquared() > DEGENERATE_SQ) back.normalize() else UP
    }

    /** Victim root rotation: yaw only, same convention as `Frame.fromYawPitch`. */
    fun victimRotation(yawDeg: Double): Quaternion = Frame.fromYawPitch(com.astarworks.astera.core.domain.geometry.Vec3.ZERO, yawDeg, 0.0).rotation

    /** `rotate(-yaw) * (world - feet)`. */
    fun pointToLocal(world: Vec3, feet: Vec3, yawDeg: Double): Vec3 =
        victimRotation(yawDeg).conjugate().rotate(world - feet)

    fun directionToLocal(world: Vec3, yawDeg: Double): Vec3 = victimRotation(yawDeg).conjugate().rotate(world)
}
