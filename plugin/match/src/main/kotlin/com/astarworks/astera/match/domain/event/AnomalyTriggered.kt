package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.EventRoute
import com.astarworks.astera.core.domain.game.InstanceId

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.anomaly.AnomalyId
import com.astarworks.astera.match.domain.anomaly.AnomalyType
import java.time.Instant

/**
 * An anomaly just fired inside a live match (Wave 5c).
 *
 * Published by `TriggerAnomalyUseCase` once the
 * [com.astarworks.astera.match.application.service.AnomalyScheduler] picks a
 * descriptor for the current tick. Downstream subscribers (HUD overlay, audio
 * cue, score modifiers in Wave 6+) react without coupling to the scheduler
 * directly.
 *
 * The actual attribute-modifier application is handled by
 * `ResolveAnomalyEffectUseCase`; this event is the *announcement*, not the
 * effect itself.
 *
 * @property anomalyId Runtime id assigned at trigger time (UUID-derived).
 * @property matchId The match the anomaly belongs to.
 * @property type Which anomaly flavor fired (carries the i18n display key).
 * @property durationTicks How long the anomaly's modifier will remain
 *   applied. Subscribers may schedule UI tear-downs against this.
 * @property atTick Server tick at which the anomaly fired.
 */
public data class AnomalyTriggered(
    val anomalyId: AnomalyId,
    val matchId: MatchId,
    val type: AnomalyType,
    val durationTicks: Long,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Instance(InstanceId(matchId.value))
}
