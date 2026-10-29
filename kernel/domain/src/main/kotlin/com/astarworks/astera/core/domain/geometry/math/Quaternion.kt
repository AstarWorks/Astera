package com.astarworks.astera.core.domain.geometry.math

import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unit-quaternion orientation, double precision. Identity is `(0, 0, 0, 1)`
 * — Mojang's wire convention `(x, y, z, w)`.
 *
 * Right-handed rotation: a positive angle about an axis carries the basis axes
 * forward -> left -> up -> forward (+angle about up carries forward toward left).
 * `a * b` applies `b` first, then `a` (Hamilton product).
 */
public data class Quaternion(
    public val x: Double,
    public val y: Double,
    public val z: Double,
    public val w: Double,
) {
    /** Hamilton product: the resulting rotation applies [o] first, then `this`. */
    public operator fun times(o: Quaternion): Quaternion = Quaternion(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    /** Rotates [v] by this (assumed unit) quaternion. */
    public fun rotate(v: Vec3): Vec3 {
        // t = 2 * (q.xyz x v); v' = v + w*t + q.xyz x t
        val tx = 2.0 * (y * v.z - z * v.y)
        val ty = 2.0 * (z * v.x - x * v.z)
        val tz = 2.0 * (x * v.y - y * v.x)
        return Vec3(
            v.x + w * tx + (y * tz - z * ty),
            v.y + w * ty + (z * tx - x * tz),
            v.z + w * tz + (x * ty - y * tx),
        )
    }

    public fun conjugate(): Quaternion = Quaternion(-x, -y, -z, w)

    public fun lengthSquared(): Double = x * x + y * y + z * z + w * w

    /** Unit-length copy; a degenerate zero quaternion becomes [IDENTITY]. */
    public fun normalized(): Quaternion {
        val l2 = lengthSquared()
        if (l2 == 0.0) return IDENTITY
        val inv = 1.0 / sqrt(l2)
        return Quaternion(x * inv, y * inv, z * inv, w * inv)
    }

    public fun dot(o: Quaternion): Double = x * o.x + y * o.y + z * o.z + w * o.w

    /** Smallest rotation angle (radians, `[0, 2*PI]` mapped to `[0, PI]`) between this orientation and [o]. */
    public fun angleTo(o: Quaternion): Double {
        val d = abs(dot(o)).coerceIn(0.0, 1.0)
        return 2.0 * acos(d)
    }

    public companion object {
        public val IDENTITY: Quaternion = Quaternion(0.0, 0.0, 0.0, 1.0)

        /** Rotation of [radians] about [axis] (need not be unit; zero axis gives identity). */
        public fun axisAngle(axis: Vec3, radians: Double): Quaternion {
            val a = axis.normalize()
            if (a.lengthSquared() == 0.0) return IDENTITY
            val h = radians / 2.0
            val s = sin(h)
            return Quaternion(a.x * s, a.y * s, a.z * s, cos(h))
        }

        /**
         * Rotation vector: the direction is the axis, the length the angle in degrees (right-hand rule). The authored form of
         * a track key, so a key is an axis-angle and never an Euler triple. Zero gives identity.
         */
        public fun fromRotationVector(degrees: Vec3): Quaternion {
            val len = sqrt(degrees.lengthSquared())
            return if (len < 1e-12) IDENTITY else axisAngle(degrees, Math.toRadians(len))
        }

        /** Shortest-path spherical interpolation; `t` is not clamped. */
        public fun slerp(a: Quaternion, b: Quaternion, t: Double): Quaternion {
            var d = a.dot(b)
            val bb = if (d < 0.0) { d = -d; Quaternion(-b.x, -b.y, -b.z, -b.w) } else b
            if (d > 0.9995) {
                return Quaternion(
                    a.x + (bb.x - a.x) * t,
                    a.y + (bb.y - a.y) * t,
                    a.z + (bb.z - a.z) * t,
                    a.w + (bb.w - a.w) * t,
                ).normalized()
            }
            val theta = acos(d.coerceIn(-1.0, 1.0))
            val sinTheta = sin(theta)
            val wa = sin((1.0 - t) * theta) / sinTheta
            val wb = sin(t * theta) / sinTheta
            return Quaternion(
                a.x * wa + bb.x * wb,
                a.y * wa + bb.y * wb,
                a.z * wa + bb.z * wb,
                a.w * wa + bb.w * wb,
            )
        }
    }
}
