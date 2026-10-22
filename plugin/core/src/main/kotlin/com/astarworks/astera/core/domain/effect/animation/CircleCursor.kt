package com.astarworks.astera.core.domain.effect.animation

import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Mutable per-instance state of an [AnimatedShape.Circle] (RTM `CircleParticleSetData`
 * + `OrthonormalBasis`, ADR-0194). Created once per instance and kept advancing across
 * every sampled point, so a repeatedly re-played animation (a projectile trail)
 * continues the orbit instead of restarting it.
 */
public class CircleCursor(private val shape: AnimatedShape.Circle, direction: Vec3, random: Random) {

    private var degree: Int = random.nextInt(360)
    private val degreeSign: Int = if (random.nextBoolean()) 1 else -1
    private var radius: Double =
        if (shape.maxRadius > shape.minRadius) random.nextDouble(shape.minRadius, shape.maxRadius) else shape.minRadius
    private var radiusSign: Int = -1 // RTM starts with positiveRadiusAcceleration = false

    /** RTM basis: `u` = the unit direction, `w` = `u x v` for a vector `v` perpendicular to `u`. */
    public val u: Vec3 = direction.normalize()
    public val w: Vec3 = u.cross(perpendicularTo(u)).normalize()

    /** Advance the angle one step and return it in degrees (RTM `nextDegree`; negative values are valid). */
    public fun nextDegree(): Int {
        degree += shape.speed * degreeSign
        return degree % 360
    }

    /** Advance the radius one step (RTM `nextRadius`). */
    public fun nextRadius(): Double {
        val min = shape.minRadius
        val max = shape.maxRadius
        if (shape.repeatRadiusAcceleration) {
            radius += shape.radiusAcceleration * radiusSign
            // RTM reflects with `max - (min - radius)`, which pushes the radius further
            // out of range; the intended mirror image is used here (deviation, ADR-0194).
            if (radius >= max) {
                radius = max - (radius - max)
                radiusSign = -radiusSign
            }
            if (radius <= min) {
                radius = min + (min - radius)
                radiusSign = -radiusSign
            }
        } else {
            radius += shape.radiusAcceleration
            if (radius >= max) radius = min + (radius - max)
            if (radius <= min) radius = max - (min - radius)
        }
        return radius
    }

    /** Next point of the orbit around [center]. */
    public fun nextPoint(center: Vec3): Vec3 {
        val radian = Math.toRadians(nextDegree().toDouble())
        val r = nextRadius()
        return center + u * (cos(radian) * r) + w * (sin(radian) * r)
    }

    private companion object {
        /** RTM `computeOrthogonalBasis`: a vector perpendicular to [u]. */
        fun perpendicularTo(u: Vec3): Vec3 =
            if (abs(u.x) > abs(u.y)) Vec3(-u.y, u.x, 0.0) else Vec3(0.0, -u.z, u.y)
    }
}
