package com.astarworks.astera.core.application.skillengine.runtime.player

import java.util.SplittableRandom

/** Deterministic random stream (C4d): the same seed gives the same draws on every host. W1-19 refines the per-(cast, op, i, call site) keying. */
public class SeededStream(seed: Long) {
    private val rng = SplittableRandom(seed)

    public fun nextDouble(): Double = rng.nextDouble()

    /** An independent stream keyed by [key] (op index, occurrence, call site). */
    public fun fork(key: Long): SeededStream = SeededStream(rng.split().nextLong() xor key)
}
