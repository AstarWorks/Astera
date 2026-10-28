package com.astarworks.astera.core.domain.geometry.math

import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The one place that turns directions into orientations and orientations back into named directions (ADR-0303).
 *
 * Local convention of every [Frame]: +X = left, +Y = up, +Z = forward. A frame is an origin plus a basis, and a basis is two
 * axes: [basis] builds the quaternion from them, [axis] reads one back.
 */
public object Basis {
    private val X = Vec3(1.0, 0.0, 0.0)
    private val Y = Vec3(0.0, 1.0, 0.0)
    private val Z = Vec3(0.0, 0.0, 1.0)

    /**
     * Orientation whose local +X is [x] and local +Y is [y] with its component along [x] removed; local +Z = x cross y.
     * Null when either is zero or they are parallel (no unique plane).
     */
    public fun basis(x: Vec3, y: Vec3): Quaternion? {
        val ux = x.normalize()
        if (ux.lengthSquared() == 0.0) return null
        val py = y - ux * y.dot(ux)
        if (py.lengthSquared() < 1e-12) return null
        val uy = py.normalize()
        return fromColumns(ux, uy, ux.cross(uy))
    }

    /**
     * Orientation whose forward (+Z) is [forward] with up as close to [up] as possible; a forward parallel to [up] picks a
     * stable alternate. Zero forward gives identity. Built on [basis] with left = up x forward.
     */
    public fun facing(forward: Vec3, up: Vec3 = Y): Quaternion {
        val f = forward.normalize()
        if (f.lengthSquared() == 0.0) return Quaternion.IDENTITY
        var left = up.cross(f)
        if (left.lengthSquared() < 1e-12) left = (if (abs(f.x) < 0.9) X else Z).cross(f)
        return basis(left, f.cross(left)) ?: Quaternion.IDENTITY
    }

    /**
     * World direction of the named local axis of [frame]: `forward back up down left right`, `x y z` (= left up forward),
     * each optionally prefixed with `-`. Null for any other name.
     */
    public fun axis(frame: Frame, name: String): Vec3? {
        val negative = name.startsWith("-")
        val local = when (name.removePrefix("-")) {
            "x", "left" -> X
            "right" -> X * -1.0
            "y", "up" -> Y
            "down" -> Y * -1.0
            "z", "forward" -> Z
            "back" -> Z * -1.0
            else -> return null
        }
        val world = frame.directionToWorld(local)
        return if (negative) world * -1.0 else world
    }

    /** Rotation matrix with the given orthonormal columns to a unit quaternion. */
    private fun fromColumns(x: Vec3, y: Vec3, z: Vec3): Quaternion {
        val m00 = x.x; val m01 = y.x; val m02 = z.x
        val m10 = x.y; val m11 = y.y; val m12 = z.y
        val m20 = x.z; val m21 = y.z; val m22 = z.z
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
}
