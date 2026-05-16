package com.astarworks.astera.domain.model.geometry

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

    companion object {
        val ZERO: Vec3 = Vec3(0.0, 0.0, 0.0)
    }
}
