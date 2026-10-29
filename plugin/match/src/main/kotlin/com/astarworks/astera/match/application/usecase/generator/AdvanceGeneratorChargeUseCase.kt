package com.astarworks.astera.match.application.usecase.generator

import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.attribute.number
import com.astarworks.astera.core.domain.attribute.setNumber
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.domain.event.GeneratorChargeAdvanced
import com.astarworks.astera.match.domain.generator.GeneratorAttributes
import com.astarworks.astera.match.domain.generator.StarGenerator

/**
 * Advance every [StarGenerator] in a match by [deltaTicks] (Wave 5c).
 *
 * For each generator:
 *
 *   1. Read the current [GeneratorAttributes.ENERGY] (in ticks; defaults to 0).
 *   2. Compute the effective period in ticks as
 *      `period_in_ticks * effective(GeneratorAttributes.PERIOD_MULTIPLIER)`.
 *      A missing or non-positive multiplier degrades gracefully to `1.0`
 *      (identity) so freshly created containers spawn at their base period.
 *   3. Add [deltaTicks] to energy. If the new energy crosses the threshold,
 *      delegate to [SpawnResourceUseCase] and carry the overshoot
 *      (`newEnergy - effectivePeriod`) into the next cycle — this keeps the
 *      long-run spawn rate exact when `deltaTicks` is larger than 1.
 *   4. Write the (post-spawn) energy back via [setNumber] so the container
 *      remains the single source of truth.
 *   5. Publish [GeneratorChargeAdvanced] so HUD widgets can update their bars.
 *
 * Only one spawn fires per call, even if energy crossed two periods — the
 * scheduler runs at 1-tick granularity in production and the
 * `deltaTicks=1` invariant means that case cannot arise; the carry handles
 * sub-tick rounding when the period multiplier produces a non-integer
 * effective period.
 *
 * # Modifier seeding contract
 *
 * `SimpleAttributeContainer.effectiveNumber` returns `base * MULTIPLY...`, and
 * `base` defaults to `0.0` when no [com.astarworks.astera.core.domain.attribute.AttributeValue.NumberValue]
 * is stored under the key. A modifier-only attribute therefore evaluates to
 * `0.0`, which this use case treats as "no value" and falls back to identity
 * `1.0`. **Callers that overlay a MULTIPLY modifier MUST seed the base value
 * to `1.0` first** — see how `SuddenDeathAcceleratorUseCase` (Phase B worker
 * γ) wires the Sudden Death accelerator. Without the seed, a `MULTIPLY 0.5`
 * modifier silently degrades to identity.
 */
public class AdvanceGeneratorChargeUseCase(
    private val generators: IGeneratorRepository,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
    private val spawn: SpawnResourceUseCase,
    private val matchWorlds: () -> com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository?,
) {

    public fun advance(matchId: MatchId, deltaTicks: Long) {
        require(deltaTicks >= 0) { "deltaTicks must be >= 0 (was $deltaTicks)" }
        if (deltaTicks == 0L) return

        val now: Long = scheduler.currentTick()

        // Wave generators are driven by StarWaveService, not by a charge period.
        for (generator in generators.listForMatch(matchId).filter { it.wave == null }) {
            val world = matchWorlds()?.findByMatch(matchId)?.worldId ?: continue
            scheduler.run(regionScope(world, generator.position), kotlin.time.Duration.ZERO) {
            val currentEnergy: Double = generator.attributes
                .number(GeneratorAttributes.ENERGY) ?: 0.0

            val rawPeriodMultiplier: Double = generator.attributes
                .effectiveNumber(GeneratorAttributes.PERIOD_MULTIPLIER)
            val periodMultiplier: Double =
                if (rawPeriodMultiplier > 0.0) rawPeriodMultiplier else 1.0

            // Base period -> ticks. 1 tick = 50 ms.
            val basePeriodTicks: Double =
                generator.period.inWholeMilliseconds / 50.0
            val effectivePeriodTicks: Double = basePeriodTicks * periodMultiplier

            val newEnergy: Double = currentEnergy + deltaTicks

            val postSpawnEnergy: Double =
                if (effectivePeriodTicks > 0.0 && newEnergy >= effectivePeriodTicks) {
                    spawn.spawn(generator)
                    newEnergy - effectivePeriodTicks
                } else {
                    newEnergy
                }

            generator.attributes.setNumber(GeneratorAttributes.ENERGY, postSpawnEnergy)

            eventBus.publish(
                GeneratorChargeAdvanced(
                    generatorId = generator.id,
                    matchId = matchId,
                    newEnergyTicks = postSpawnEnergy,
                    atTick = now,
                ),
            )
            }
        }
    }
}
