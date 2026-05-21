package com.astarworks.astera.match.application.port.inbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.replay.RecordedReplay
import com.astarworks.astera.core.domain.replay.ReplayError
import com.astarworks.astera.core.domain.replay.ReplayFormat

/**
 * Inbound port: lifecycle handle for match recording (Wave 5v, ADR-0088).
 *
 * The default implementation
 * (`com.astarworks.astera.match.application.service.replay.ReplayCaptureEngine`)
 * subscribes to the domain event bus on construction; callers (typically
 * `AsteraMatchPlugin` lifecycle hooks or ops `/astera-match replay` admin
 * commands) toggle which matches are actively buffered via the methods
 * below.
 *
 * Wave 5v ships with **opt-in** recording: a `MatchStarted` listener does
 * NOT auto-start recording; ops must call `startRecording` explicitly via
 * the command tree or a config flag, keeping memory usage bounded for the
 * Phase 1 PoC. Auto-record-everything is left to Phase 4 + ADR (it
 * intersects with privacy / EU storage decisions).
 */
public interface IMatchRecorder {

    /**
     * Begin buffering events + key frames for [matchId] under [format].
     *
     * Idempotent on `matchId` — calling twice with the same id replaces
     * the active recording's format but keeps the existing event buffer
     * (useful for upgrade-mid-capture from sparse → full).
     */
    public fun startRecording(matchId: MatchId, format: ReplayFormat)

    /**
     * Finalise the recording for [matchId] and persist via the registered
     * `IMatchReplayRepository`. Returns the [RecordedReplay] on success.
     *
     * After this returns the in-memory buffer for [matchId] is dropped.
     * Subsequent `isRecording(matchId)` returns false.
     */
    public fun stopRecording(matchId: MatchId): Result<RecordedReplay, ReplayError>

    /** True iff `startRecording` has been called for [matchId] and not yet stopped. */
    public fun isRecording(matchId: MatchId): Boolean

    /** Snapshot of every match currently being recorded. */
    public fun activeRecordings(): Collection<MatchId>
}
