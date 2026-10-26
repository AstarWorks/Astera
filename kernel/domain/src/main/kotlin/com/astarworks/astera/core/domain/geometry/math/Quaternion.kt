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
 * Right-handed rotation: a positive angle about an axis rotates
 * counter-clockwise when looking down the axis toward the origin.
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

        private val UNIT_X = Vec3(1.0, 0.0, 0.0)
        private val UNIT_Y = Vec3(0.0, 1.0, 0.0)
        private val UNIT_Z = Vec3(0.0, 0.0, 1.0)

        /** Rotation of [radians] about [axis] (need not be unit; zero axis gives identity). */
        public fun axisAngle(axis: Vec3, radians: Double): Quaternion {
            val a = axis.normalize()
            if (a.lengthSquared() == 0.0) return IDENTITY
            val h = radians / 2.0
            val s = sin(h)
            return Quaternion(a.x * s, a.y * s, a.z * s, cos(h))
        }

        /**
         * Minecraft convention. Yaw about +Y (0 = facing +Z/south, 90 = facing
         * -X/west), pitch about local X (positive = look down), roll about
         * local Z. Composed as `yaw * pitch * roll`.
         */
        public fun fromEulerDegrees(yaw: Double, pitch: Double, roll: Double): Quaternion {
            val qy = axisAngle(UNIT_Y, Math.toRadians(-yaw))
            val qp = axisAngle(UNIT_X, Math.toRadians(pitch))
            val qr = axisAngle(UNIT_Z, Math.toRadians(roll))
            return (qy * qp * qr).normalized()
        }

        /**
         * Orientation mapping local +Z to [forward], with local +Y as close
         * to [up] as possible (local +X = up x forward, i.e. "left").
         * A zero [forward] gives identity; a forward parallel to [up] picks
         * a stable alternate up.
         */
        public fun lookRotation(forward: Vec3, up: Vec3 = Vec3(0.0, 1.0, 0.0)): Quaternion {
            val f = forward.normalize()
            if (f.lengthSquared() == 0.0) return IDENTITY
            var left = up.cross(f)
            if (left.lengthSquared() < 1e-12) {
                val alt = if (abs(f.x) < 0.9) UNIT_X else UNIT_Z
                left = alt.cross(f)
            }
            left = left.normalize()
            val u = f.cross(left)
            // Rotation matrix columns: left (x), u (y), f (z)
            val m00 = left.x; val m01 = u.x; val m02 = f.x
            val m10 = left.y; val m11 = u.y; val m12 = f.y
            val m20 = left.z; val m21 = u.z; val m22 = f.z
            val trace = m00 + m11 + m22
            val q = if (trace > 0.0) {
                val s = sqrt(trace + 1.0) * 2.0
                Quaternion((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25 * s)
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(1.0 + m00 - m11 - m22) * 2.0
                Quaternion(0.25 * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
            } else if (m11 > m22) {
                val s = sqrt(1.0 + m11 - m00 - m22) * 2.0
                Quaternion((m01 + m10) / s, 0.25 * s, (m12 + m21) / s, (m02 - m20) / s)
            } else {
                val s = sqrt(1.0 + m22 - m00 - m11) * 2.0
                Quaternion((m02 + m20) / s, (m12 + m21) / s, 0.25 * s, (m10 - m01) / s)
            }
            return q.normalized()
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
