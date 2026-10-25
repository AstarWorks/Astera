package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.replay.ReplayId
import java.time.Instant

/**
 * Emitted by `IMatchRecorder.startRecording` (Wave 5v, ADR-0088).
 *
 * Lives in `core.domain.event` so cross-plugin listeners (ops dashboard,
 * audit log, future Discord notifier) can subscribe without depending on
 * `:plugin:match` symbols. The recorder itself owns the capture lifecycle;
 * subscribers should treat this event as a notification, not as a hook
 * point to alter capture behaviour.
 *
 * @property formatDescriptor a stable text discriminator for the
 *   `ReplayFormat` chosen (e.g. `"event-log-keyframe"` /
 *   `"full-per-tick"`). The full `ReplayFormat` sealed is intentionally
 *   NOT embedded — domain events stay primitive-typed for cross-plugin
 *   wire stability (same posture as Wave 5g audit-log entries).
 */
public data class MatchRecordingStarted(
    val replayId: ReplayId,
    val matchId: MatchId,
    val formatDescriptor: String,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
