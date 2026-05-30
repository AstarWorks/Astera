package com.astarworks.astera.combat.application.service.damagebonus

import com.astarworks.astera.combat.application.port.outbound.IDamageBonusSource
import com.astarworks.astera.combat.application.port.outbound.IFocusFireTracker
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * [IDamageBonusSource] backed by [IFocusFireTracker] (Wave K 5integration-wire,
 * ADR-0128).
 *
 * Delegates to [IFocusFireTracker.bonusMultiplierFor] — the tracker already
 * owns the windowed contribution accounting (Wave 5combat-depth /
 * ADR-0105). This source is stateless; it does not subscribe to any event.
 *
 * The tracker itself listens to `DamageDealt` elsewhere (Wave 5combat-depth
 * wiring); this source is the *read* side that turns "is this hit inside an
 * active focus window?" into a multiplier the damage pipeline can fold in.
 *
 * # Why a separate source (and not "tracker calls applyDamage with bonus")
 *
 * The tracker is consulted on every damage attempt, but it doesn't *cause*
 * the attempt — `ApplyDamageUseCase` is the canonical entry. Keeping the
 * lookup inside a `IDamageBonusSource` means:
 *
 *  - the tracker stays a pure read API (no callbacks),
 *  - tests of the tracker do not have to know about the damage use case,
 *  - operators can swap the source for a different focus-fire formula
 *    (e.g. a tournament profile) without touching the tracker.
 *
 * # Attacker / victim derivation
 *
 * `DamageSource` is the only place the attacker id lives at this point in
 * the pipeline (mirror of `attackerIdOrNull()`). Environmental damage has
 * no attacker → no focus-fire credit → returns `1.0`.
 */
public class FocusFireBonusSource(
    private val tracker: IFocusFireTracker,
) : IDamageBonusSource {

    override fun bonusFor(attempt: DamageAttempt, nowTick: Long): Double {
        val attackerId = attackerOf(attempt) ?: return 1.0
        return tracker.bonusMultiplierFor(
            attacker = attackerId,
            victim = attempt.targetId,
            atTick = nowTick,
        )
    }

    private fun attackerOf(attempt: DamageAttempt): PlayerId? = when (val src = attempt.source) {
        is DamageSource.Item -> src.attackerId
        is DamageSource.Skill -> src.casterId
        is DamageSource.Projectile -> src.ownerId
        is DamageSource.Block -> src.attackerId
        is DamageSource.StatusEffectTick,
        is DamageSource.Explosion,
        is DamageSource.Barrier,
        is DamageSource.Environment,
        -> null
    }
}
