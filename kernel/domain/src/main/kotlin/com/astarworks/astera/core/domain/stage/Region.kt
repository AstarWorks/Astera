package com.astarworks.astera.core.domain.stage

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * Axis-aligned bounding box in world coordinates. Useful for "is this player
 * inside the spawn area?", "did the cart enter the goal region?", etc.
 *
 * Bounds are inclusive on both ends. [name] is the stage-YAML region name
 * (`base-red`, `wool-a`, ...) that game modes use to find their objective
 * zones; geometry-only callers leave it `null`.
 */
public data class Region(val min: Vec3, val max: Vec3, val name: String? = null) {

    init {
        require(min.x <= max.x) { "min.x ($min.x) must be <= max.x ($max.x)" }
        require(min.y <= max.y) { "min.y ($min.y) must be <= max.y ($max.y)" }
        require(min.z <= max.z) { "min.z ($min.z) must be <= max.z ($max.z)" }
    }

    public fun contains(point: Vec3): Boolean =
        point.x in min.x..max.x &&
        point.y in min.y..max.y &&
        point.z in min.z..max.z

    public val volume: Double
        get() = (max.x - min.x) * (max.y - min.y) * (max.z - min.z)
}
