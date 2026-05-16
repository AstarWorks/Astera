package com.astarworks.astera.domain.model.projectile

/**
 * When a projectile stops existing.
 *
 * Independent of [MotionProfile] / [HitProfile] — a long-range bullet might
 * be `Distance(100.0)`; a quick-fading magic spark might be `Ticks(20)`;
 * a piercing arrow might be `UntilHit`.
 */
public sealed class LifetimePolicy {

    /** Live for at most [maxTicks] ticks regardless of distance / hits. */
    public data class Ticks(val maxTicks: Int) : LifetimePolicy() {
        init {
            require(maxTicks > 0) { "maxTicks must be > 0, got $maxTicks" }
        }
    }

    /** Live until the projectile has traveled [maxBlocks] blocks. */
    public data class Distance(val maxBlocks: Double) : LifetimePolicy() {
        init {
            require(maxBlocks > 0.0) { "maxBlocks must be > 0, got $maxBlocks" }
        }
    }

    /** Live until the first hit (cancelled at impact). */
    public data object UntilHit : LifetimePolicy()
}
