package com.astarworks.astera.combat.application.port.outbound

/**
 * Random source for damage rule decisions (crit roll, dodge chance, ...).
 *
 * Modelled as a port (rather than passing `kotlin.random.Random` directly)
 * so unit tests can supply a deterministic queue of values and assert exact
 * branch behaviour. Production binding is `KotlinRandomDamageRandomizer`
 * (worker β); tests use `FakeDamageRandomizer(values: List<Double>)`.
 */
public interface IDamageRandomizer {
    /** Returns a uniform double in `[0.0, 1.0)`. */
    public fun nextDouble(): Double
}
