package com.astarworks.astera.combat.application.service.damagebonus

import com.astarworks.astera.core.application.port.outbound.EventDelivery

import com.astarworks.astera.combat.application.port.outbound.IDamageBonusSource
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.ComboRouteCompleted
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * [IDamageBonusSource] that subscribes to [ComboRouteCompleted] and arms the
 * route's [ComboRouteCompleted.bonusDamageMultiplier] for the player's
 * **next** damage attempt (Wave K 5integration-wire, ADR-0128).
 *
 * # Why a subscriber, not a pull from `IComboTracker`
 *
 * `IComboTracker` resets the player's state the same tick a route fires
 * (ADR-0105 §"One-shot fire") — by the time the next damage hits, the
 * tracker has no memory of which route just completed. The bonus has to
 * live somewhere outside the tracker; per `ComboRouteCompleted`'s docstring
 * ("The tracker stores this so the damage pipeline can apply it on the
 * player's next strike (subscriber-side state)") that "somewhere" is
 * this source.
 *
 * # Lifecycle of an armed bonus
 *
 *  1. Player chains hits — `IComboTracker.recordHit` advances the state.
 *  2. State matches a route — tracker publishes [ComboRouteCompleted]
 *     **and** resets the player's state (one-shot).
 *  3. This source records `pending[playerId] = bonusDamageMultiplier`.
 *  4. The player's next [DamageAttempt] (regardless of weapon — the chain
 *     was already weapon-bound when scored) consumes the pending bonus.
 *  5. Source clears `pending[playerId]`; next attempt sees `1.0` again.
 *
 * # Why "regardless of weapon" on consume
 *
 * The tracker's chain is weapon-keyed (switching weapons mid-chain resets
 * before the route ever fires), so by the time we publish the bonus the
 * "right weapon" guarantee already held. Re-checking on consume would
 * double-gate and (worse) silently swallow the bonus if the player drew
 * their finisher item between the chain landing and the strike — that's
 * a UX trap for ranged finishers in particular. The bonus expires on
 * the next attempt regardless; the player can't bank it.
 *
 * # Thread model
 *
 * Single-threaded (main thread). The `pending` map is not synchronised
 * — same posture as the three trackers per ADR-0105 §"Main-thread only".
 */
public class ComboBonusSource(
    eventBus: IDomainEventBus,
) : IDamageBonusSource {

    private val pending: MutableMap<PlayerId, Double> = HashMap()

    /**
     * Active subscription to [ComboRouteCompleted]. Held so [AsteraCombatPlugin]
     * can cancel it during `onDisable` along with the rest of its subscriptions.
     */
    public val subscription: IDomainEventBus.Subscription =
        eventBus.subscribe(ComboRouteCompleted::class, EventDelivery.Global) { event -> arm(event) }

    override fun bonusFor(attempt: DamageAttempt, nowTick: Long): Double {
        val attackerId = attackerOf(attempt) ?: return 1.0
        val armed = pending.remove(attackerId) ?: return 1.0
        return armed
    }

    private fun arm(event: ComboRouteCompleted) {
        // Replace any prior pending bonus — chaining two routes back-to-back
        // is unusual (the tracker resets on fire) but if it happens, the
        // latest declared multiplier wins.
        pending[event.playerId] = event.bonusDamageMultiplier
    }

    /**
     * Attacker derivation mirrors `DamageSource.attackerIdOrNull()` but is
     * inlined here to keep the source free of imports beyond the port
     * surface. Environmental / barrier / status-tick damage has no combo
     * attacker so the source contributes `1.0`.
     */
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

    /** Test-only window into the in-flight pending map. */
    internal fun pendingFor(playerId: PlayerId): Double? = pending[playerId]
}
