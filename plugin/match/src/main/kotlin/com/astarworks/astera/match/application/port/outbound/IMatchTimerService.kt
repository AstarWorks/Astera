package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.Result

/**
 * Outbound port: shift an in-flight match's phase timer.
 *
 * Wave 5g (Core hardening) lands the port + `FakeMatchTimerService` so the
 * TimeWarp anomaly variant has a Fake-level wiring path. The Wave 6a impl
 * will plug into the existing
 * [com.astarworks.astera.match.application.usecase.match.PhaseTimerUseCase]
 * machinery; for Wave 5g it is a sealed contract awaiting impl.
 *
 * Per ADR-0072: this port is committed pre-Wave-6 so anomaly use cases can
 * already depend on the signature. The default `NoopMatchTimerService` swallows
 * calls (registered by `AsteraMatchPlugin` until the Wave 6a impl lands).
 */
public interface IMatchTimerService {

    /**
     * Shift the current phase timer for [matchId] by [deltaTicks].
     *
     * Positive [deltaTicks] extends the phase (slows the match down — typical
     * TimeWarp "anomaly slows time"); negative shortens it (rushes the
     * phase). Implementations clamp to keep the elapsed/remaining invariants
     * sane.
     *
     * Returns [TimerShiftError.UnknownMatch] if [matchId] is not currently
     * timed (not started yet, already ended) or [TimerShiftError.Storage]
     * on backend failure.
     */
    public fun shiftPhaseTimer(matchId: MatchId, deltaTicks: Int): Result<Unit, TimerShiftError>
}

public sealed class TimerShiftError {
    public data class UnknownMatch(public val matchId: MatchId) : TimerShiftError()
    public data class Storage(public val detail: String) : TimerShiftError()
}
