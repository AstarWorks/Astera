package com.astarworks.astera.match.application.usecase.generator

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.match.domain.event.ResourceSpawned
import com.astarworks.astera.match.domain.generator.GeneratorAttributes
import com.astarworks.astera.match.domain.generator.StarGenerator

/**
 * Publish a [ResourceSpawned] event for the given generator (Wave 5c).
 *
 * Pure application logic: read the generator's effective
 * [GeneratorAttributes.AMOUNT_MULTIPLIER] modifier, multiply the base
 * [StarGenerator.amount], floor + coerce to at least 1 so anomalies that
 * lower the multiplier never zero-out spawns, then publish.
 *
 * Kept as its own use case rather than inlined into the charge-advancement
 * loop so other producers (e.g. an admin `/astera spawn-now` command, a
 * boon that fires an extra spawn outside the normal cadence) can trigger
 * a spawn without re-implementing the multiplier math.
 *
 * The "multiplier missing or zero ⇒ identity 1.0" guard is the same shape
 * used by [AdvanceGeneratorChargeUseCase] for the period multiplier; both
 * are tolerant of a freshly-created container that has not yet had a
 * [GeneratorAttributes.AMOUNT_MULTIPLIER] value written.
 *
 * Modifier-seeding caveat: a caller that overlays a `MULTIPLY 2.0` modifier
 * on [GeneratorAttributes.AMOUNT_MULTIPLIER] **must seed the base value to
 * `1.0` first**. With no base, `effectiveNumber` evaluates to `0.0`, which
 * this use case treats as "absent" and degrades to identity 1.0 — see the
 * matching note in [AdvanceGeneratorChargeUseCase].
 */
public class SpawnResourceUseCase(
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
) {

    public fun spawn(generator: StarGenerator) {
        val rawMultiplier: Double = generator.attributes
            .effectiveNumber(GeneratorAttributes.AMOUNT_MULTIPLIER)
        val amountMultiplier: Double =
            if (rawMultiplier > 0.0) rawMultiplier else 1.0

        val finalAmount: Int = (generator.amount * amountMultiplier)
            .toInt()
            .coerceAtLeast(1)

        eventBus.publish(
            ResourceSpawned(
                generatorId = generator.id,
                matchId = generator.matchId,
                position = generator.position,
                currency = generator.currency,
                amount = finalAmount,
                atTick = scheduler.currentTick(),
            ),
        )
    }
}
