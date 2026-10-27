package com.astarworks.astera.combat.application.usecase.damage

import com.astarworks.astera.core.application.service.combat.CombatContextResolver
import com.astarworks.astera.core.domain.combat.CombatContext
import com.astarworks.astera.combat.application.port.outbound.DamageApplicationError
import com.astarworks.astera.combat.application.port.outbound.DamageOutcome
import com.astarworks.astera.combat.application.port.outbound.IArmorEquipmentProvider
import com.astarworks.astera.combat.application.port.outbound.IDamageBonusSource
import com.astarworks.astera.combat.application.port.outbound.IDamageGateway
import com.astarworks.astera.combat.application.port.outbound.IEntityDamageGateway
import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.combat.domain.damage.attackerIdOrNull
import com.astarworks.astera.combat.domain.event.CriticalHit
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.DamageMitigated
import com.astarworks.astera.combat.domain.event.FriendlyFireBlocked
import com.astarworks.astera.combat.domain.event.KnockbackApplied
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.combat.domain.rule.DamageRule
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IScheduler
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
    private val scheduler: IScheduler,
    /**
     * Wave 5e (ADR-0058 §armor): defender's attribute container is fetched
     * here and threaded into [DamageRule.resolve] so
     * [com.astarworks.astera.combat.domain.rule.ArmorReductionModifier]
     * can read `Attributes.RESIST_*` modifiers (sourced
     * `"armor:<slot>:<id>"` by `ApplyArmorModifiersUseCase` on
     * `LoadoutChanged`). Nullable so tests that don't care about resist
     * lookup can pass null — the modifier then falls back to a spec walk.
     */
    private val containerStore: IPlayerAttributeContainerStore? = null,
    /**
     * Wave K 5integration-wire (ADR-0128): per-attempt damage bonus
     * contributors consulted after [DamageRule.resolve] returns but before
     * the gateway applies the hit. Empty by default — Wave 5combat-depth's
     * combo / focus-fire / target-lock trackers register their
     * [IDamageBonusSource] impls in `AsteraCombatPlugin` and the use case
     * picks them up at Phase C wiring time.
     *
     * Multipliers are applied in registration order (left to right). Order
     * is observable only when two sources are "armed" simultaneously (the
     * leftmost source consumes its bonus first); the final magnitude is
     * the product regardless.
     */
    private val bonusSources: List<IDamageBonusSource> = emptyList(),
    /**
     * WP3: non-player living entities (mobs). When set and
     * [IEntityDamageGateway.isEntityTarget] holds for the target, the attempt
     * takes [executeOnEntity] instead of the player path.
     */
    private val entityDamageGateway: IEntityDamageGateway? = null,
    /** F1-D (ADR-0183): admin god mode; a god target is immune like the `immunity` status. */
    private val godMode: com.astarworks.astera.combat.application.port.outbound.IGodModeService? = null,
    /** F3-11: players in the lobby training range are immune to damage from anyone (no PvP there). */
    private val protection: com.astarworks.astera.combat.application.port.outbound.IDamageProtection? = null,
    /**
     * Spawn protection after a (re)spawn, match start or reconnect: the target takes no damage from anything
     * except the void (a protected player must still be killable by falling out of the arena).
     */
    private val spawnProtection: com.astarworks.astera.combat.application.port.outbound.IDamageProtection? = null,
    /**
     * ADR-0237: decides the [CombatContext] stamped on every published [DamageDealt] / [PlayerKilled].
     * Defaults to the (possibly late-registered, hence possibly null) [teamLookup]; wiring passes a
     * per-call lookup because the match plugin registers its lookup after combat enables.
     */
    private val contexts: CombatContextResolver = CombatContextResolver { teamLookup },
) {
    public fun execute(attempt: DamageAttempt): Result<DamageOutcome, ApplyDamageError> {
        if (entityDamageGateway?.isEntityTarget(attempt.targetId) == true) {
            return executeOnEntity(entityDamageGateway, attempt)
        }
        // 1. Immunity check (status effect, admin god mode or a protected zone such as the training range)
        if (protection?.isProtected(attempt.targetId) == true ||
            godMode?.isGod(attempt.targetId) == true ||
            statusContainer.has(attempt.targetId, IMMUNITY_ID)
        ) {
            return Result.failure(ApplyDamageError.TargetImmune(attempt.targetId))
        }
        if (attempt.source !is DamageSource.Environment.Void && spawnProtection?.isProtected(attempt.targetId) == true) {
            return Result.failure(ApplyDamageError.TargetImmune(attempt.targetId))
        }

        // 2. Resolve via pipeline
        val attackerId = attempt.source.attackerIdOrNull()
        val attackerTeam = attackerId?.let { teamLookup?.teamOf(it) }
        val defenderTeam = teamLookup?.teamOf(attempt.targetId)
        val armor = armorProvider.equippedArmor(attempt.targetId)
        val defenderContainer = containerStore?.containerFor(attempt.targetId)

        val resolution = damageRule.resolve(
            attempt = attempt,
            defenderArmor = armor,
            defenderContainer = defenderContainer,
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

        // 5b. Damage-bonus sources (Wave K 5integration-wire, ADR-0128).
        //     Folded *after* the rule has resolved so combat-depth trackers
        //     (combo / focus-fire / target-lock) amplify the *post-armor*
        //     damage — multiplying *before* armor would let bonuses turn a
        //     hit that should have been fully absorbed into a chip hit.
        //     Bonus sources may consume internal state (combo one-shot,
        //     target-lock single-use); they are called exactly once per
        //     attempt in registration order.
        val atTick = scheduler.currentTick()
        val context = contexts.betweenPlayers(attackerId, attempt.targetId)
        val ruleAmount = resolution.resolved.finalAmount
        var bonusFactor = 1.0
        for (src in bonusSources) {
            val m = src.bonusFor(attempt, atTick)
            require(m.isFinite() && m >= 0.0) {
                "IDamageBonusSource.${src::class.simpleName} returned non-finite or negative multiplier: $m"
            }
            bonusFactor *= m
        }
        val boostedResolved = if (bonusFactor == 1.0) {
            resolution.resolved
        } else {
            ResolvedDamage(
                finalAmount = (ruleAmount * bonusFactor).coerceAtLeast(0.0),
                attribute = resolution.resolved.attribute,
                wasBlocked = resolution.resolved.wasBlocked,
                wasCritical = resolution.resolved.wasCritical,
            )
        }

        // 6. Delegate to gateway, then emit main + knockback + death events
        return when (val gw = damageGateway.apply(attempt.targetId, boostedResolved, attempt.source, attempt.ignoreInvulnerability)) {
            is Result.Success -> {
                eventBus.publish(
                    DamageDealt(
                        attacker = attackerId,
                        target = attempt.targetId,
                        resolved = boostedResolved,
                        source = attempt.source,
                        atTick = atTick,
                        context = context,
                    ),
                )
                resolution.knockback?.let { push ->
                    knockbackGateway.applyPush(attempt.targetId, push)
                    eventBus.publish(
                        KnockbackApplied(
                            target = attempt.targetId,
                            push = push,
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
                            atTick = atTick,
                            context = context,
                        ),
                    )
                }
                Result.success(gw.value)
            }
            is Result.Failure -> Result.failure(ApplyDamageError.GatewayFailed(gw.error))
        }
    }

    /**
     * Mob path (WP3): same rule pipeline minus player-only state (no attribute
     * container, no equipped armor, no status immunity). Publishes
     * [DamageDealt] / [KnockbackApplied] but never [PlayerKilled] — a mob death
     * is the host's `EntityDeathEvent` (Paper kill credit comes from
     * `LivingEntity.damage(amount, attacker)`).
     */
    private fun executeOnEntity(
        gateway: IEntityDamageGateway,
        attempt: DamageAttempt,
    ): Result<DamageOutcome, ApplyDamageError> {
        val attackerId = attempt.source.attackerIdOrNull()
        val resolution = damageRule.resolve(
            attempt = attempt,
            defenderArmor = null,
            defenderContainer = null,
            attackerTeam = attackerId?.let { teamLookup?.teamOf(it) },
            defenderTeam = null,
        )
        if (resolution.resolved.wasBlocked) {
            return Result.failure(ApplyDamageError.FriendlyFireBlocked(attempt.targetId))
        }
        val atTick = scheduler.currentTick()
        // A training dummy is its own context; any other entity counts only through a match participant.
        val context = if (gateway.isTrainingTarget(attempt.targetId)) CombatContext.Training else contexts.againstEntity(attackerId)
        return when (val gw = gateway.apply(attempt.targetId, resolution.resolved, attempt.source)) {
            is Result.Success -> {
                eventBus.publish(
                    DamageDealt(
                        attacker = attackerId,
                        target = attempt.targetId,
                        resolved = resolution.resolved,
                        source = attempt.source,
                        atTick = atTick,
                        context = context,
                    ),
                )
                resolution.knockback?.let { push -> knockbackGateway.applyPush(attempt.targetId, push) }
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
