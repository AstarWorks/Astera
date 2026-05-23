package com.astarworks.astera.items.application.port.outbound

import com.astarworks.astera.items.domain.refinement.RefinementSpec
import com.astarworks.astera.items.domain.refinement.SubStatKey
import com.astarworks.astera.items.domain.refinement.SubStatRoll

/**
 * Random source for refinement-side decisions (Wave 5refine, ADR-0100).
 *
 * Modelled as a port (rather than passing `kotlin.random.Random` directly)
 * so unit tests can supply a deterministic sequence and assert exact branch
 * behaviour. Mirrors the
 * [com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer]
 * pattern from Wave 4a.
 *
 *   - Production binding: `KotlinRandomRefinementRandomizer`.
 *   - Test binding: `FakeRefinementRandomizer(values)`.
 */
public interface IRefinementRandomizer {

    /**
     * Roll a fresh set of [RefinementSpec.subStatCount] sub-stats picked
     * uniformly from [RefinementSpec.candidateSubStats]. Returns the chosen
     * keys *with their magnitudes* — magnitude bounds (per-stat min/max) are
     * left to the implementation Phase 1, so tests can fix exact values.
     */
    public fun rollSubStats(spec: RefinementSpec): List<SubStatRoll>

    /**
     * Roll one additional reroll grain for [key]. Returns the per-grain
     * magnitude that the use case sums into the existing [SubStatRoll]. The
     * caller honors [RefinementSpec.maxRollsPerStat].
     */
    public fun rollSubStatGrain(spec: RefinementSpec, key: SubStatKey): Double
}
