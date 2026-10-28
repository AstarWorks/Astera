package com.astarworks.astera.core.domain.skillengine.world

import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.math.floor

/** One block position. */
public data class BlockCell(val x: Int, val y: Int, val z: Int) {
    public fun distanceSquaredTo(p: Vec3): Double {
        val dx = x + 0.5 - p.x
        val dy = y + 0.5 - p.y
        val dz = z + 0.5 - p.z
        return dx * dx + dy * dy + dz * dz
    }

    public companion object {
        public fun containing(p: Vec3): BlockCell = BlockCell(floor(p.x).toInt(), floor(p.y).toInt(), floor(p.z).toInt())
    }
}
