package com.astarworks.astera.core.application.skillengine.runtime

import java.util.UUID

/**
 * Lets casts see and veto damage that reaches their caster (`window` op, `listen: incoming_hit`). The damage pipeline
 * calls it once per hit that is about to land.
 */
public fun interface IncomingHitHook {
    /**
     * [amount] is about to land on [target] from [attacker]. Returns the multiplier the casts of [target] impose
     * (1 = untouched, 0 = negated). Windows that pass queue their `do:` ops on the casting side.
     */
    public fun intercept(target: UUID, attacker: UUID, amount: Double): Double
}
