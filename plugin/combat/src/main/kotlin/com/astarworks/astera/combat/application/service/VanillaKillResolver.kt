package com.astarworks.astera.combat.application.service

import com.astarworks.astera.core.application.service.combat.CombatContextResolver
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.domain.event.PlayerHealthChanged
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.core.domain.status.StatusEffectId

/**
 * Attribution captured from the vanilla damage event that is about to be
 * re-routed into `IPlayerHealthGateway` (see `BukkitEventAdapter`).
 *
 * @property killer the attacking player (or projectile shooter); null for
 *   environment / mob damage.
 */
public data class PendingVanillaAttribution(
    val victim: PlayerId,
    val killer: PlayerId?,
    val atTick: Long,
)

/**
 * Pure rule deciding when an `IPlayerHealthGateway` mutation is a death that
 * `ApplyDamageUseCase` did not already announce.
 *
 * `ApplyDamageUseCase` / `PaperDamageGateway` write the HP container directly
 * and publish `PlayerKilled` themselves, so they never raise
 * `PlayerHealthChanged`. Everything that does (vanilla melee, mob arrows,
 * fall / lava / void, status DoT, admin) reaches 0 HP through the health
 * gateway; this resolver turns that transition into exactly one
 * `PlayerKilled` (only the `oldHp > 0 -> newHp <= 0` edge qualifies).
 */
public class VanillaKillResolver(
    /** ADR-0237: stamps the kill's context; the default lookup-less resolver files everything under `Lobby`. */
    private val contexts: CombatContextResolver = CombatContextResolver { null },
    /**
     * Last player who damaged the victim within [VOID_ATTRIBUTION_TICKS] of `atTick` (damage history). A void fall
     * credits that player so the KILL norma and star burst treat it as their kill.
     */
    private val recentAttacker: (victim: PlayerId, atTick: Long) -> PlayerId? = { _, _ -> null },
) {

    public fun resolve(event: PlayerHealthChanged, pending: PendingVanillaAttribution?): PlayerKilled? {
        if (event.oldHp <= 0.0 || event.newHp > 0.0) return null
        if (!isDamageSource(event.source)) return null
        val attribution = pending?.takeIf {
            it.victim == event.playerId && event.atTick - it.atTick in 0..ATTRIBUTION_WINDOW_TICKS
        }
        val healthSource = event.source
        val dotOrigin = (healthSource as? HealthChangeSource.Status)?.originPlayerId
            ?.takeIf { it != event.playerId }
        val meleeKiller = attribution?.killer
        val voidKiller = if (meleeKiller == null && dotOrigin == null && healthSource == VOID_SOURCE) {
            recentAttacker(event.playerId, event.atTick)?.takeIf { it != event.playerId }
        } else {
            null
        }
        val killer = meleeKiller ?: dotOrigin ?: voidKiller
        val source: DamageSource = when {
            meleeKiller != null -> DamageSource.Item(ItemId(VANILLA_MELEE_ITEM), meleeKiller)
            healthSource is HealthChangeSource.Status -> statusSource(healthSource.statusId, dotOrigin)
            else -> DamageSource.Environment.Generic
        }
        return PlayerKilled(
            victim = event.playerId,
            killer = killer,
            source = source,
            atTick = event.atTick,
            context = contexts.betweenPlayers(killer, event.playerId),
        )
    }

    private fun isDamageSource(source: HealthChangeSource): Boolean = when (source) {
        HealthChangeSource.Regen,
        HealthChangeSource.Respawn,
        HealthChangeSource.Item,
        -> false
        is HealthChangeSource.Other -> source.label.startsWith("vanilla-")
        else -> true
    }

    private fun statusSource(statusId: String, origin: PlayerId?): DamageSource =
        runCatching { DamageSource.StatusEffectTick(StatusEffectId(statusId), origin) }
            .getOrDefault(DamageSource.Environment.Generic)

    public companion object {
        /** Attribution is only valid for the tick of the hit (plus one for scheduling skew). */
        public const val ATTRIBUTION_WINDOW_TICKS: Long = 1L

        /** A void fall is credited to an attacker this recent (10 s). */
        public const val VOID_ATTRIBUTION_TICKS: Long = 10L * 20L

        private val VOID_SOURCE: HealthChangeSource = HealthChangeSource.Other("vanilla-void")

        public const val VANILLA_MELEE_ITEM: String = "vanilla-melee"
    }
}
