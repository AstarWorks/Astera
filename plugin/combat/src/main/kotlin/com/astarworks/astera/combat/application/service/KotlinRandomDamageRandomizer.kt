package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer
import kotlin.random.Random

/**
 * Production binding of [IDamageRandomizer] backed by `kotlin.random.Random`.
 *
 * Pass a [seed] to obtain a reproducible stream — used by integration tests
 * and balance simulations that need deterministic crit / dodge rolls.
 * Otherwise [Random.Default] is used and rolls are non-reproducible.
 *
 * Unit tests covering specific branch conditions should prefer the
 * `FakeDamageRandomizer(values)` queue fixture in `testfixtures/`.
 */
public class KotlinRandomDamageRandomizer(seed: Long? = null) : IDamageRandomizer {
    private val rng: Random = if (seed != null) Random(seed) else Random.Default

    override fun nextDouble(): Double = rng.nextDouble()
}
