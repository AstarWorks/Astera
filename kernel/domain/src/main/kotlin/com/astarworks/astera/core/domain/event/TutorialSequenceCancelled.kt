package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import java.time.Instant

/**
 * Tutorial sequencer ended without completing every step (Wave 5p, ADR-0088).
 *
 * Published by [com.astarworks.astera.core.application.port.inbound.ITutorialSequencerService.cancel]
 * and by the listener that observes player disconnect mid-sequence. The HUD
 * adapter clears any in-flight tutorial overlay on this signal.
 */
public data class TutorialSequenceCancelled(
    public val playerId: PlayerId,
    public val scriptId: TutorialScriptId,
    public val stepIndex: Int,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
