package com.astarworks.astera.core.domain.replay

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * Quantised position snapshot for [KeyFrame] / [ReplayEvent.PlayerMoved]
 * (Wave 5v, ADR-0094).
 *
 * Stored as three `Float`s rather than `Double`s to halve the per-coord JSONB
 * footprint at the cost of ~7 decimal digits of precision — well beyond what
 * replay playback can render visibly (Minecraft world coordinates rarely
 * need sub-millimetre accuracy in a clip viewer).
 *
 * The Wave 5v "4 player × 1 hour ≈ 5 MB" budget assumes
 * `Vec3Snapshot` here + delta-encoded `PlayerMoved` between key frames; a
 * `Double` triplet would push that to ~10 MB without observable benefit.
 */
public data class Vec3Snapshot(val x: Float, val y: Float, val z: Float) {

    public fun toVec3(): Vec3 = Vec3(x.toDouble(), y.toDouble(), z.toDouble())

    public companion object {
        public fun fromVec3(v: Vec3): Vec3Snapshot = Vec3Snapshot(
            x = v.x.toFloat(),
            y = v.y.toFloat(),
            z = v.z.toFloat(),
        )
    }
}
