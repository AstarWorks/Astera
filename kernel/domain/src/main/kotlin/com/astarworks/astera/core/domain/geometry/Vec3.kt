package com.astarworks.astera.core.domain.geometry

import kotlin.math.sqrt

/**
 * 3D position in world space, double precision.
 *
 * Defined here (rather than reusing JOML or org.bukkit.Location) so the domain
 * stays free of external geometry dependencies. Adapter layers convert to/from
 * platform-specific types.
 */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Double): Vec3 = Vec3(x * scalar, y * scalar, z * scalar)

    fun length(): Double = sqrt(lengthSquared())

    /** Avoids sqrt for distance comparisons. */
    fun lengthSquared(): Double = x * x + y * y + z * z

    /**
     * Returns the unit vector. **Returns [ZERO] for the zero vector** to
     * avoid `NaN` propagation; callers that need to detect the zero case
     * must check [lengthSquared] first.
     */
    fun normalize(): Vec3 {
        val l2 = lengthSquared()
        if (l2 == 0.0) return ZERO
        val inv = 1.0 / sqrt(l2)
        return Vec3(x * inv, y * inv, z * inv)
    }

    fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z

    fun cross(other: Vec3): Vec3 = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    fun distance(other: Vec3): Double = (this - other).length()

    fun distanceSquared(other: Vec3): Double = (this - other).lengthSquared()

    companion object {
        val ZERO: Vec3 = Vec3(0.0, 0.0, 0.0)
    }
}
