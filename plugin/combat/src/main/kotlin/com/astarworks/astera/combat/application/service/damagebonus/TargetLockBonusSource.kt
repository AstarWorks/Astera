package com.astarworks.astera.combat.application.service.damagebonus

import com.astarworks.astera.combat.application.port.outbound.IDamageBonusSource
import com.astarworks.astera.combat.application.port.outbound.ITargetLockController
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * [IDamageBonusSource] backed by [ITargetLockController] (Wave K
 * 5integration-wire, ADR-0128).
 *
 * Routes ranged-only damage attempts (`DamageSource.Projectile`) through
 * the controller's `consumeBonusFor(attacker, victim)` `PlayerId` overload.
 * Melee / skill / explosion attempts skip the controller — the lock-on
 * mechanic exists to reward sustained aim, which only makes sense for
 * projectiles.
 *
 * # Why ranged-only here, not in the controller
 *
 * The controller's `consumeBonusFor` is symmetric across attack kinds
 * (the bonus is armed on acquisition, not on weapon type — Wave 6b's
 * Paper adapter validates the weapon at `tickAim` time). Filtering here
 * means a future scope expansion (e.g. crossbow-skill amplification)
 * can land as a second source instead of a controller mutation.
 *
 * # Single-use consume
 *
 * `consumeBonusFor` is one-shot per acquisition (per ADR-0105
 * §"Target lock bonus is single-use per acquisition"); subsequent hits
 * by the same attacker against the same victim return `1.0` until a
 * new lock is acquired.
 */
public class TargetLockBonusSource(
    private val controller: ITargetLockController,
) : IDamageBonusSource {

    override fun bonusFor(attempt: DamageAttempt, nowTick: Long): Double {
        val src = attempt.source
        // Ranged-only: only projectile attempts qualify for target-lock bonus.
        if (src !is DamageSource.Projectile) return 1.0
        val attackerId: PlayerId = src.ownerId
        return controller.consumeBonusFor(
            attackerId = attackerId,
            victimId = attempt.targetId,
            nowTick = nowTick,
        )
    }
}
