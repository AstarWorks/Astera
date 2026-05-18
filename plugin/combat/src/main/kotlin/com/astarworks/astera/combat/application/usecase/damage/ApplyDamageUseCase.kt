package com.astarworks.astera.combat.application.usecase.damage

import com.astarworks.astera.combat.application.port.outbound.DamageApplicationError
import com.astarworks.astera.combat.application.port.outbound.DamageOutcome
import com.astarworks.astera.combat.application.port.outbound.IArmorEquipmentProvider
import com.astarworks.astera.combat.application.port.outbound.IDamageGateway
import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.attackerIdOrNull
import com.astarworks.astera.combat.domain.event.CriticalHit
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.DamageMitigated
import com.astarworks.astera.combat.domain.event.FriendlyFireBlocked
import com.astarworks.astera.combat.domain.event.KnockbackApplied
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.combat.domain.rule.DamageRule
import com.astarworks.astera.combat.domain.status.StatusEffectId
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Resolves and applies a [DamageAttempt] through [DamageRule] and the
 * outbound damage gateway, publishing a rich set of sub-events along the way.
 *
 * Wave 5a rewrites the previous (Phase 1 PoC) implementation: instead of
 * emitting only [DamageDealt] / [PlayerKilled], the use case now emits one
 * event per pipeline concern so analytics / kill-feed / replay can attribute
 * outcomes without re-running the rule:
 *
 *  - [FriendlyFireBlocked] when the policy blocks the hit (and no damage is
 *    applied)
 *  - [DamageMitigated] — one per [com.astarworks.astera.combat.domain.damage.MitigationApplied]
 *    in the resolution
 *  - [CriticalHit] when the resolution flagged crit
 *  - [DamageDealt] — main event, always
 *  - [KnockbackApplied] when the resolution carries an impulse
 *  - [PlayerKilled] when the gateway returns [DamageOutcome.Killed]
 *
 * ADR-0030 §8 commits Wave 6's BukkitEventAdapter to hijack every vanilla
 * EntityDamageEvent / EntityDamageByEntityEvent / PlayerDeathEvent and route
 * through this use case — after Wave 6 there is exactly one damage path in
 * Astera, and every event carries a fully-typed [DamageSource].
 */
public class ApplyDamageUseCase(
    private val damageGateway: IDamageGateway,
    private val knockbackGateway: IKnockbackGateway,
    private val statusContainer: IStatusEffectContainer,
    private val damageRule: DamageRule,
    private val armorProvider: IArmorEquipmentProvider,
    private val teamLookup: IMatchTeamLookup?,
    private val eventBus: IDomainEventBus,
) {
    public fun execute(attempt: DamageAttempt): Result<DamageOutcome, ApplyDamageError> {
        // 1. Immunity check
        if (statusContainer.has(attempt.targetId, IMMUNITY_ID)) {
            return Result.failure(ApplyDamageError.TargetImmune(attempt.targetId))
        }

        // 2. Resolve via pipeline
        val attackerId = attempt.source.attackerIdOrNull()
        val attackerTeam = attackerId?.let { teamLookup?.teamOf(it) }
        val defenderTeam = teamLookup?.teamOf(attempt.targetId)
        val armor = armorProvider.equippedArmor(attempt.targetId)

        val resolution = damageRule.resolve(
            attempt = attempt,
            defenderArmor = armor,
            attackerTeam = attackerTeam,
            defenderTeam = defenderTeam,
        )

        // 3. Friendly-fire short-circuit
        if (resolution.resolved.wasBlocked) {
            if (attackerId != null) {
                eventBus.publish(
                    FriendlyFireBlocked(
                        attacker = attackerId,
                        intendedTarget = attempt.targetId,
                        source = attempt.source,
                    ),
                )
            }
            return Result.failure(ApplyDamageError.FriendlyFireBlocked(attempt.targetId))
        }

        // 4. Mitigation audit events (one per recorded mitigation)
        resolution.mitigations.forEach { mit ->
            eventBus.publish(
                DamageMitigated(
                    target = attempt.targetId,
                    originalAmount = attempt.baseProfile.base,
                    finalAmount = resolution.resolved.finalAmount,
                    reason = mit.reason,
                    source = attempt.source,
                ),
            )
        }

        // 5. CriticalHit before DamageDealt so listeners can flag the main event
        if (resolution.wasCritical) {
            eventBus.publish(
                CriticalHit(
                    attacker = attackerId,
                    target = attempt.targetId,
                    multiplier = resolution.criticalMultiplier,
                    source = attempt.source,
                ),
            )
        }

        // 6. Delegate to gateway, then emit main + knockback + death events
        return when (val gw = damageGateway.apply(attempt.targetId, resolution.resolved, attempt.source)) {
            is Result.Success -> {
                eventBus.publish(
                    DamageDealt(
                        attacker = attackerId,
                        target = attempt.targetId,
                        resolved = resolution.resolved,
                        source = attempt.source,
                    ),
                )
                resolution.knockback?.let { impulse ->
                    knockbackGateway.applyImpulse(attempt.targetId, impulse)
                    eventBus.publish(
                        KnockbackApplied(
                            target = attempt.targetId,
                            impulse = impulse,
                            source = attempt.source,
                        ),
                    )
                }
                if (gw.value is DamageOutcome.Killed) {
                    eventBus.publish(
                        PlayerKilled(
                            victim = attempt.targetId,
                            killer = attackerId,
                            source = attempt.source,
                            atTick = System.currentTimeMillis(),
                        ),
                    )
                }
                Result.success(gw.value)
            }
            is Result.Failure -> Result.failure(ApplyDamageError.GatewayFailed(gw.error))
        }
    }

    private companion object {
        val IMMUNITY_ID = StatusEffectId("immunity")
    }
}

/** Expected failure modes of [ApplyDamageUseCase.execute]. */
public sealed class ApplyDamageError {
    public data class TargetImmune(val targetId: PlayerId) : ApplyDamageError()
    public data class FriendlyFireBlocked(val targetId: PlayerId) : ApplyDamageError()
    public data class GatewayFailed(val cause: DamageApplicationError) : ApplyDamageError()
}
