package com.astarworks.astera.core.domain.effect

/**
 * Cancellation handle returned by every scheduled effect emitter
 * (`IParticleScheduler.schedule`, `ISoundScheduler.schedule`,
 * `IEffectOrchestrator.play`).
 *
 * Wave 5effects (ADR-0109). The shape mirrors `IScheduler.TaskHandle` so
 * archetype handlers and phase listeners can store the handle and cancel on
 * phase end, mob despawn, or player disconnect.
 *
 * # Why a dedicated type
 *
 * `IScheduler.TaskHandle` already covers one-shot / repeating callbacks, but
 * the effect-scheduler ports also represent **composite** schedules
 * (`Burst`, `Pulse`, `RingExpansion`, `Sequence`, `Parallel`) whose
 * cancellation must atomically tear down every underlying task. A dedicated
 * `EffectHandle` keeps that lifetime separate from the raw scheduler handle
 * so a single `cancel()` aborts the composite, not just the next pending
 * iteration.
 *
 * # Re-cancellation
 *
 * Implementations MUST be idempotent: calling [cancel] on an already-
 * cancelled handle is a no-op, never an error. After cancellation
 * [isActive] returns `false` forever.
 */
public interface EffectHandle {
    /** Cancel the underlying schedule. Idempotent. */
    public fun cancel()

    /**
     * `true` while the effect is still scheduled to fire (or repeat).
     * Becomes `false` after [cancel] or after the effect naturally
     * completes (durationTicks elapsed, last `Sequence` step run, …).
     */
    public val isActive: Boolean

    public companion object {
        /**
         * No-op handle for "effect was never scheduled because the spec was
         * empty / a precondition failed" branches. Always inactive.
         */
        public val NONE: EffectHandle = object : EffectHandle {
            override fun cancel() {}
            override val isActive: Boolean = false
        }
    }
}
