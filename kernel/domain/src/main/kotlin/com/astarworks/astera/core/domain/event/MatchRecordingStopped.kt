package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.game.InstanceId

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.replay.ReplayId
import java.time.Instant

/**
 * Emitted by `IMatchRecorder.stopRecording` on success (Wave 5v, ADR-0088).
 *
 * Stat sinks (`/metrics` exporter, audit log, ops dashboard) subscribe and
 * record (matchId, durationTicks, sizeBytes) without holding a reference
 * to the heavy `RecordedReplay` blob — the recorded value already lives
 * in `match_replays` and can be re-fetched by `IMatchReplayRepository
 * .findByMatchId` if a subscriber needs the body.
 *
 * `sizeBytes` is the recorder's best-effort estimate of the serialised
 * payload (see `RecordedReplay.sizeBytes` for caveats); subscribers
 * comparing it against budgets (5MB target) should treat ±10% as noise.
 */
public data class MatchRecordingStopped(
    val replayId: ReplayId,
    val matchId: MatchId,
    val startTick: Long,
    val endTick: Long,
    val eventCount: Int,
    val keyFrameCount: Int,
    val sizeBytes: Int,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Instance(InstanceId(matchId.value))
}
