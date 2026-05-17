package com.astarworks.astera.match.domain.match

/**
 * Wave 4.5 (R3.5, ADR-0027): timing constants for the match state machine.
 *
 * Ticks are Minecraft server ticks (20 TPS); COUNTDOWN_TICKS=600 → 30s.
 * Phase 3 may make these configurable per Stage (e.g. ranked = 60s
 * countdown, casual = 15s). Until then, these are global.
 *
 * See also: ADR-0019 (Unified Scheduler) for how these tick values are
 * consumed by the [com.astarworks.astera.core.application.port.outbound.IScheduler]
 * via the absolute-tick reading from `currentTick()` rather than `runLater`.
 */
public object PhaseTimings {
    /** Duration of the COUNTDOWN phase before auto-transition to ACTIVE. */
    public const val COUNTDOWN_TICKS: Long = 600L // 30s @ 20 TPS

    /** Duration of the SUDDEN_DEATH phase before auto-transition to ENDED. */
    public const val SUDDEN_DEATH_TICKS: Long = 1200L // 60s @ 20 TPS

    /** First team to reach this kill/score count wins (or triggers SUDDEN_DEATH). */
    public const val SCORE_THRESHOLD: Int = 10
}
