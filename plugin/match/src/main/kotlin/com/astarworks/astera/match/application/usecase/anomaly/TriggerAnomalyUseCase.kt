package com.astarworks.astera.match.application.usecase.anomaly

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.anomaly.AnomalyDescriptor
import com.astarworks.astera.match.domain.anomaly.AnomalyId
import com.astarworks.astera.match.domain.event.AnomalyTriggered
import java.util.UUID

/**
 * Announce that an [AnomalyDescriptor] has fired inside a match (Wave 5c).
 *
 * Pure application logic: mint a fresh [AnomalyId], publish an
 * [AnomalyTriggered] event, return the id so the caller (the
 * [com.astarworks.astera.match.application.service.AnomalyScheduler]) can pass
 * it on to [ResolveAnomalyEffectUseCase] and to a delayed expiration callback.
 *
 * The actual attribute-modifier application lives in
 * [ResolveAnomalyEffectUseCase] — this use case is the announcement only.
 * Splitting the two keeps the announcement contract narrow enough that
 * non-effect subscribers (HUD, audio, telemetry) can watch the event without
 * picking up an effect-resolution dependency.
 */
public class TriggerAnomalyUseCase(
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
) {

    /**
     * Mint an [AnomalyId], publish [AnomalyTriggered], return the id.
     *
     * The id is derived from `UUID.randomUUID()` — its canonical string form
     * (lowercase hex + dashes) already satisfies [AnomalyId]'s validation
     * regex, so no extra massaging is required.
     */
    public fun trigger(matchId: MatchId, descriptor: AnomalyDescriptor): AnomalyId {
        val anomalyId = AnomalyId(UUID.randomUUID().toString())
        eventBus.publish(
            AnomalyTriggered(
                anomalyId = anomalyId,
                matchId = matchId,
                type = descriptor.type,
                durationTicks = descriptor.durationTicks,
                atTick = scheduler.currentTick(),
            ),
        )
        return anomalyId
    }
}
