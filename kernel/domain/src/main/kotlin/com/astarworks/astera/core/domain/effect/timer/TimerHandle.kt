package com.astarworks.astera.core.domain.effect.timer

/**
 * Lifecycle handle for an [com.astarworks.astera.core.application.port.outbound.effect.ICountdownTimer]
 * registration. Supports pause / resume / cancel in addition to the
 * base "cancel + introspect" surface.
 *
 * Wave 5effects (ADR-0109).
 */
public interface TimerHandle {
    public val id: CountdownTimerId

    /** Ticks remaining (post-pause if paused). 0 = completed. */
    public val remainingTicks: Long

    /** True between start and (completion | cancel). */
    public val isActive: Boolean

    /** True if paused but not cancelled. */
    public val isPaused: Boolean

    /** Pause the countdown. Idempotent. */
    public fun pause()

    /** Resume from the last pause. No-op if not paused. */
    public fun resume()

    /** Stop the countdown without firing onComplete. Idempotent. */
    public fun cancel()
}
