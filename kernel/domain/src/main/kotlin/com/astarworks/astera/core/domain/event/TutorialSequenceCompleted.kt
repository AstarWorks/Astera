package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import java.time.Instant

/**
 * Tutorial sequencer walked every step to the end (Wave 5p, ADR-0088).
 *
 * Distinguishable from [TutorialSequenceCancelled] so analytics can compute
 * "completion rate per script".
 */
public data class TutorialSequenceCompleted(
    public val playerId: PlayerId,
    public val scriptId: TutorialScriptId,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
