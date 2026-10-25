package com.astarworks.astera.core.domain.effect.animation

import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.math.abs

/** Two unit vectors perpendicular to [axis] and to each other (same construction as [CircleCursor]). */
internal class PlaneBasis(axis: Vec3) {
    val axis: Vec3 = axis.normalize()
    val a: Vec3 = this.axis.cross(perpendicularTo(this.axis)).normalize()
    val b: Vec3 = this.axis.cross(a).normalize()

    private companion object {
        fun perpendicularTo(u: Vec3): Vec3 =
            if (abs(u.x) > abs(u.y)) Vec3(-u.y, u.x, 0.0) else Vec3(0.0, -u.z, u.y)
    }
}
