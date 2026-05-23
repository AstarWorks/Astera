package com.astarworks.astera.items.testfixtures.refinement

import com.astarworks.astera.items.application.port.outbound.IRefinementRandomizer
import com.astarworks.astera.items.domain.refinement.RefinementSpec
import com.astarworks.astera.items.domain.refinement.SubStatKey
import com.astarworks.astera.items.domain.refinement.SubStatRoll
import java.util.ArrayDeque

/**
 * Deterministic [IRefinementRandomizer] for unit tests (Wave 5refine,
 * ADR-0100). Mirrors the existing
 * [com.astarworks.astera.combat.testfixtures.FakeDamageRandomizer] pattern:
 * a queue of pre-recorded outcomes that the use case consumes in order.
 *
 * Usage:
 *
 * ```kotlin
 * val fake = FakeRefinementRandomizer(
 *     subStatPlans = ArrayDeque(listOf(
 *         listOf(SubStatRoll(SubStatKey.ATTACK, 30.0, 1)),
 *         listOf(SubStatRoll(SubStatKey.ATTACK, 80.0, 1)),  // candidate of second call
 *     )),
 *     grainMagnitudes = ArrayDeque(listOf(15.0, 22.0)),
 * )
 * ```
 *
 * Calls outside the queue throw — test failures are loud, not silent.
 */
public class FakeRefinementRandomizer(
    private val subStatPlans: ArrayDeque<List<SubStatRoll>> = ArrayDeque(),
    private val grainMagnitudes: ArrayDeque<Double> = ArrayDeque(),
) : IRefinementRandomizer {

    public fun queueRollSubStats(rolls: List<SubStatRoll>) {
        subStatPlans.add(rolls)
    }

    public fun queueGrainMagnitude(value: Double) {
        grainMagnitudes.add(value)
    }

    override fun rollSubStats(spec: RefinementSpec): List<SubStatRoll> {
        check(subStatPlans.isNotEmpty()) {
            "FakeRefinementRandomizer.rollSubStats() called with empty queue."
        }
        return subStatPlans.poll()
    }

    override fun rollSubStatGrain(spec: RefinementSpec, key: SubStatKey): Double {
        check(grainMagnitudes.isNotEmpty()) {
            "FakeRefinementRandomizer.rollSubStatGrain() called with empty queue."
        }
        return grainMagnitudes.poll()
    }
}
