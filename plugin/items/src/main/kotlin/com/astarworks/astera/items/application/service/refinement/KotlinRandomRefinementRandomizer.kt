package com.astarworks.astera.items.application.service.refinement

import com.astarworks.astera.items.application.port.outbound.IRefinementRandomizer
import com.astarworks.astera.items.domain.refinement.RefinementSpec
import com.astarworks.astera.items.domain.refinement.SubStatKey
import com.astarworks.astera.items.domain.refinement.SubStatRoll
import kotlin.random.Random

/**
 * Production [IRefinementRandomizer] backed by [kotlin.random.Random] (Wave
 * 5refine, ADR-0100). Tests use `FakeRefinementRandomizer` (test-fixtures).
 *
 * Phase 1 magnitude bounds are fixed at `[10.0, 100.0]` per grain — keeps the
 * arithmetic visible in player-facing numbers without yet committing to the
 * full balance ladder. The fine-grained per-stat curve lives behind a future
 * `RefinementCurveTable` once the balance review (Wave 12g analytics) has
 * data.
 */
public class KotlinRandomRefinementRandomizer(
    private val random: Random = Random.Default,
) : IRefinementRandomizer {

    override fun rollSubStats(spec: RefinementSpec): List<SubStatRoll> {
        if (spec.subStatCount == 0 || spec.candidateSubStats.isEmpty()) return emptyList()
        // Distinct pick without replacement: shuffle the candidate set and take N.
        val pool: List<SubStatKey> = spec.candidateSubStats.shuffled(random)
        return pool.take(spec.subStatCount).map { key ->
            SubStatRoll(
                key = key,
                magnitude = rollGrainMagnitude(),
                rollCount = 1,
            )
        }
    }

    override fun rollSubStatGrain(spec: RefinementSpec, key: SubStatKey): Double {
        // The caller is responsible for validating `key in spec.candidateSubStats`
        // (use case enforces; randomizer trusts the contract).
        return rollGrainMagnitude()
    }

    private fun rollGrainMagnitude(): Double {
        val low = 10.0
        val high = 100.0
        // [low, high) — adequate granularity for sub-stat readouts (Phase 1).
        return low + random.nextDouble() * (high - low)
    }
}
