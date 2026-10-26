package com.astarworks.astera.core.application.port.outbound.effect

import com.astarworks.astera.core.domain.effect.timer.CountdownTimerId
import com.astarworks.astera.core.domain.effect.timer.TimerHandle

/**
 * Outbound port: HUD ↔ audio synchronised countdown timer
 * (Wave 5effects / ADR-0109).
 *
 * # Why a dedicated port
 *
 * Match timers, skill cooldowns, betting-match opt-in windows, and
 * tutorial-step deadlines all need:
 *
 *  1. a synchronised "remaining ticks" value (so the HUD ring + sound
 *     blip + chat warning don't drift),
 *  2. per-tick callback hook (so the same source of truth drives every
 *     subscriber),
 *  3. pause/resume (match host pauses for disconnect, skill cooldown
 *     pauses during cinematic, ...).
 *
 * The raw `IScheduler.repeat(period = 1.tick)` primitive supports
 * all three but each subscriber would derive `remainingTicks` itself; this
 * port consolidates that math behind one [TimerHandle].
 */
public interface ICountdownTimer {

    /**
     * Start a countdown [id] running for [durationTicks].
     *
     *  - [onTick] fires once per tick with the **remaining** count
     *    (`durationTicks → 0`). The handle's [TimerHandle.remainingTicks]
     *    is the same value observed by [onTick].
     *  - [onComplete] fires once when the timer hits `0` (NOT fired on
     *    [TimerHandle.cancel]).
     *
     * Restarting the same [id] cancels the existing timer (no overlap).
     */
    public fun start(
        id: CountdownTimerId,
        durationTicks: Long,
        onTick: (remainingTicks: Long) -> Unit = {},
        onComplete: () -> Unit = {},
    ): TimerHandle

    /** Look up a previously started timer by id. `null` if not active. */
    public fun find(id: CountdownTimerId): TimerHandle?

    /** Convenience: pause every active timer (server-wide freeze). */
    public fun pauseAll()

    /** Convenience: resume every paused timer. */
    public fun resumeAll()
}
