package com.astarworks.astera.core.domain.replay

import com.astarworks.astera.core.domain.match.MatchId

/**
 * Finalised capture produced by `IMatchRecorder.stopRecording` (Wave 5v,
 * ADR-0094).
 *
 * Immutable; the recorder hands one of these off to
 * `IMatchReplayRepository.save` and then drops its in-memory buffer.
 * Playback consumers (Phase 4 web UI / `.replay.json` exporter) treat the
 * record as the single source of truth.
 *
 * Invariants:
 *  - `startTick <= endTick`
 *  - every `event.atTick` ∈ `[startTick .. endTick]`
 *  - every `keyFrame.tick` ∈ `[startTick .. endTick]`
 *  - events sorted by `atTick` ascending (within-tick order preserved)
 *  - keyFrames sorted by `tick` ascending
 *  - `sizeBytes >= 0`
 *
 * `sizeBytes` is the recorder's best-effort estimate of the serialised
 * JSONB payload (used for ops dashboards + the `match_replays.size_bytes`
 * column); the exact post-encoding size differs by ≤10% and is not load-
 * bearing for any decision in the runtime.
 */
public data class RecordedReplay(
    val id: ReplayId,
    val matchId: MatchId,
    val startTick: Long,
    val endTick: Long,
    val format: ReplayFormat,
    val events: List<ReplayEvent>,
    val keyFrames: List<KeyFrame>,
    val sizeBytes: Int,
) {
    init {
        require(startTick >= 0L) { "startTick must be >= 0: $startTick" }
        require(endTick >= startTick) { "endTick ($endTick) must be >= startTick ($startTick)" }
        require(sizeBytes >= 0) { "sizeBytes must be >= 0: $sizeBytes" }
        events.forEach { event ->
            require(event.atTick in startTick..endTick) {
                "event.atTick ${event.atTick} out of [$startTick..$endTick]"
            }
        }
        keyFrames.forEach { frame ->
            require(frame.tick in startTick..endTick) {
                "keyFrame.tick ${frame.tick} out of [$startTick..$endTick]"
            }
        }
    }

    /** Duration of the capture in server ticks (inclusive). */
    public val durationTicks: Long get() = endTick - startTick
}
