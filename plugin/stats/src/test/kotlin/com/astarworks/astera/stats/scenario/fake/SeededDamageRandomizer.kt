package com.astarworks.astera.stats.scenario.fake

import com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer
import kotlin.random.Random

/**
 * Deterministic [IDamageRandomizer] backed by a seeded [kotlin.random.Random].
 *
 * Wave _infra-1 / ADR-0059. Same observable behaviour as the production
 * [com.astarworks.astera.combat.application.service.KotlinRandomDamageRandomizer],
 * but with a fixed seed (default `0L`) so scenario tests asserting on crit /
 * dodge / status-roll branches stay stable across runs and JVMs.
 *
 * If a test needs a specific roll sequence (e.g. "first roll must be a
 * crit"), prefer the queue-style `FakeDamageRandomizer(values: List<Double>)`
 * fixture in `:plugin:test-fixtures` — this class is for scenarios that just
 * want determinism without caring about the exact value stream.
 */
public class SeededDamageRandomizer(seed: Long = 0L) : IDamageRandomizer {
    private val rng: Random = Random(seed)

    override fun nextDouble(): Double = rng.nextDouble()
}
