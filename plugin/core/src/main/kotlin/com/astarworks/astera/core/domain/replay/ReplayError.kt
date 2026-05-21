package com.astarworks.astera.core.domain.replay

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId

/**
 * Sealed error returned by `IMatchRecorder.stopRecording` /
 * `IMatchReplayRepository` (Wave 5v, ADR-0094).
 *
 * Per Astera coding-posture (`Result<T, E>` + sealed error hierarchies, no
 * raw JVM throwables in application APIs), every replay-pipeline call site
 * pattern-matches the variants below.
 */
public sealed interface ReplayError {
    public val messageKey: MessageKey

    /** stopRecording / save called for a match that was never recorded. */
    public data class NotRecording(val matchId: MatchId) : ReplayError {
        override val messageKey: MessageKey =
            MessageKey("astera.match.replay.error.not-recording")
    }

    /** stopRecording called for a match that has zero events AND zero key frames. */
    public data class EmptyRecording(val matchId: MatchId) : ReplayError {
        override val messageKey: MessageKey =
            MessageKey("astera.match.replay.error.empty")
    }

    /** Persistence layer rejected the write (DB error, serialisation failure). */
    public data class Storage(val reason: String) : ReplayError {
        override val messageKey: MessageKey =
            MessageKey("astera.match.replay.error.storage")
    }

    /**
     * The recorder hit the format's hard size cap (e.g. `FullPerTick`'s
     * `HARD_CAP_TICKS` reached) and stopped capture; whatever was already
     * buffered is still saved, so this is a *warning* status returned
     * alongside a successful save when callers want to surface a notice.
     *
     * Returned only by inspection paths (`isRecording` callers), never as
     * a `Result.Failure` from `stopRecording`.
     */
    public data class CapHit(val matchId: MatchId, val cappedAtTick: Long) : ReplayError {
        override val messageKey: MessageKey =
            MessageKey("astera.match.replay.error.cap-hit")
    }
}
