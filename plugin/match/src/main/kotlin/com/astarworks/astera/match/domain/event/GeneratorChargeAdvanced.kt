package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.EventRoute
import com.astarworks.astera.core.domain.game.InstanceId

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.generator.GeneratorId
import java.time.Instant

/**
 * One generator's charge meter ticked (Wave 5c).
 *
 * Published every tick the scheduler advances a generator, regardless of
 * whether the tick produced a spawn. HUD widgets subscribe to render charge
 * bars; analytics may sample it. The payload carries the *post-tick* energy
 * in ticks ([newEnergyTicks]) so subscribers do not need to track previous
 * state.
 *
 * Distinct from [ResourceSpawned]: the latter fires only when the threshold
 * was crossed and a resource was actually produced.
 */
public data class GeneratorChargeAdvanced(
    val generatorId: GeneratorId,
    val matchId: MatchId,
    val newEnergyTicks: Double,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Instance(InstanceId(matchId.value))
}
