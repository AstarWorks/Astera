package com.astarworks.astera.core.domain.geometry

/**
 * Mutable counterpart of [Vec3]. For pool reuse on hot paths
 * (per-tick projectile motion, collision math).
 *
 * Keep [Vec3] (immutable data class) as the canonical type for
 * DomainEvent fields and public APIs. Use [MutableVec3] only inside
 * pooled compute scopes:
 *
 *   val v = Vec3Pool.default.acquire()
 *   try {
 *       v.set(x, y, z)
 *       // ... math ...
 *       publishEvent(v.toVec3())
 *   } finally {
 *       Vec3Pool.default.release(v)
 *   }
 */
public class MutableVec3(
    public var x: Double = 0.0,
    public var y: Double = 0.0,
    public var z: Double = 0.0,
) {
    public fun set(x: Double, y: Double, z: Double): MutableVec3 {
        this.x = x; this.y = y; this.z = z
        return this
    }

    public fun setFrom(other: Vec3): MutableVec3 = set(other.x, other.y, other.z)

    public fun add(dx: Double, dy: Double, dz: Double): MutableVec3 {
        x += dx; y += dy; z += dz
        return this
    }

    public fun add(other: MutableVec3): MutableVec3 = add(other.x, other.y, other.z)

    public fun sub(dx: Double, dy: Double, dz: Double): MutableVec3 {
        x -= dx; y -= dy; z -= dz
        return this
    }

    public fun scale(s: Double): MutableVec3 {
        x *= s; y *= s; z *= s
        return this
    }

    public fun length(): Double = kotlin.math.sqrt(lengthSquared())

    public fun lengthSquared(): Double = x * x + y * y + z * z

    /** In-place normalize. Zero vector is left untouched (no-op) to avoid NaN. */
    public fun normalize(): MutableVec3 {
        val l2 = lengthSquared()
        if (l2 == 0.0) return this
        val inv = 1.0 / kotlin.math.sqrt(l2)
        x *= inv; y *= inv; z *= inv
        return this
    }

    public fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z

    public fun dot(other: MutableVec3): Double = x * other.x + y * other.y + z * other.z

    /** In-place cross product; uses tmp doubles to handle self-aliasing. */
    public fun cross(other: Vec3): MutableVec3 {
        val nx = y * other.z - z * other.y
        val ny = z * other.x - x * other.z
        val nz = x * other.y - y * other.x
        x = nx; y = ny; z = nz
        return this
    }

    public fun toVec3(): Vec3 = Vec3(x, y, z)

    public fun reset(): MutableVec3 = set(0.0, 0.0, 0.0)
}
