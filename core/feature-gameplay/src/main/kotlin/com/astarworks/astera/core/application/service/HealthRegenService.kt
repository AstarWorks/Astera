package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.contents.BalanceRef
import com.astarworks.astera.core.application.port.outbound.IEgoGauge
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.core.domain.player.physiology.HealthRegenConfig

/**
 * Slow natural HP regen for tracked players. "In combat" is the E.G.O gauge's
 * last-gain tick (it moves on every hit dealt or taken), so a hit either way
 * pauses regen for [HealthRegenConfig.outOfCombatDelayTicks]. Called once a
 * second by the wiring.
 */
public class HealthRegenService(
    private val health: IPlayerHealthGateway,
    private val ego: IEgoGauge,
    private val configRef: BalanceRef<HealthRegenConfig>,
) {
    public val config: HealthRegenConfig get() = configRef.current

    private val tracked: MutableSet<PlayerId> = LinkedHashSet()

    public fun track(playerId: PlayerId) {
        tracked += playerId
    }

    public fun untrack(playerId: PlayerId) {
        tracked -= playerId
    }

    public fun tick(nowTick: Long) {
        if (config.regenPerSecond <= 0.0) return
        for (id in tracked.toList()) {
            val current = health.getCurrentHealth(id) ?: continue
            val max = health.getMaxHealth(id) ?: continue
            if (current <= 0.0 || current >= max) continue
            val last = ego.lastCombatTick(id)
            if (last != null && nowTick - last < config.outOfCombatDelayTicks) continue
            health.heal(id, config.regenPerSecond.coerceAtMost(max - current), HealthChangeSource.Regen)
        }
    }
}
