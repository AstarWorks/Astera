package com.astarworks.astera.core.domain.geometry.math

import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Tiny deterministic PRNG (splitmix64). Same seed gives the same sequence on every JVM. */
public class SeededRandom(seed: Long) {
    private var state: Long = seed

    public fun nextLong(): Long {
        state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** Uniform in `[0, 1)`. */
    public fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53).toDouble())

    /** Uniform in `[min, max)`. */
    public fun nextDouble(min: Double, max: Double): Double = min + (max - min) * nextDouble()

    /** Uniform point on the unit sphere. */
    public fun nextUnitVector(): Vec3 {
        val z = nextDouble(-1.0, 1.0)
        val phi = nextDouble(0.0, 2.0 * PI)
        val r = sqrt(1.0 - z * z)
        return Vec3(r * cos(phi), r * sin(phi), z)
    }
}
