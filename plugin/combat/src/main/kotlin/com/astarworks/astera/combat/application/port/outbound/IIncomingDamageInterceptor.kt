package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Lets the skill engine veto or scale a hit that is about to land on a player (`window` op, `listen: incoming_hit`).
 * Asked by [com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase] after the friendly-fire check,
 * once per hit that has a player attacker.
 */
public fun interface IIncomingDamageInterceptor {
    /** Multiplier for [amount] aimed at [target] by [attacker]: 1 = untouched, 0 = negated. */
    public fun multiplier(target: PlayerId, attacker: PlayerId, amount: Double): Double
}
