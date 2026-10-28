package com.astarworks.astera.core.application.skillengine.runtime.player

import java.util.SplittableRandom

/**
 * Deterministic random stream (C4d): the same seed gives the same draws on every host. [fork] is a pure function of
 * (seed, key), never of how many draws were taken before it, so a chance row draws the same numbers however many events
 * ran earlier in the cast (replay equality, C8).
 */
public class SeededStream(private val seed: Long) {
    private val rng = SplittableRandom(seed)

    public fun nextDouble(): Double = rng.nextDouble()

    public fun nextLong(): Long = rng.nextLong()

    /** An independent stream keyed by [key] (op index, occurrence, call site). */
    public fun fork(key: Long): SeededStream = SeededStream(mix(seed + key * GOLDEN))

    /** An independent stream keyed by a text key such as `instance|src|tick` (FNV-1a, stable across JVMs). */
    public fun fork(key: String): SeededStream {
        var h = -0x340d631b7bdddcdbL
        for (c in key) {
            h = (h xor c.code.toLong()) * 0x100000001b3L
        }
        return fork(h)
    }

    private companion object {
        const val GOLDEN: Long = -0x61c8864680b583ebL

        fun mix(z0: Long): Long {
            var z = z0
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
    }
}
