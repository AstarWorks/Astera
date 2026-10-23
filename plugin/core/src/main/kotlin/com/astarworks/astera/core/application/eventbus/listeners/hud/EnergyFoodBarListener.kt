package com.astarworks.astera.core.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.FoodMutationError
import com.astarworks.astera.core.application.port.outbound.IPlayerFoodGateway
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerActiveWeaponChanged
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.event.PlayerRespawned
import com.astarworks.astera.core.domain.event.ResourceRegenned
import com.astarworks.astera.core.domain.event.ResourceSpent
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.EnergyFoodBar
import com.astarworks.astera.core.domain.resource.ResourceType

/**
 * The energy bar's one owner (F2-2, ADR-0194): mirrors each joined player's
 * energy onto the vanilla food bar, `foodLevel = EnergyFoodBar.foodLevelFor(energy, max)`.
 *
 * Event-driven for responsiveness ([ResourceSpent] / [ResourceRegenned] of
 * energy, weapon change because the cap can differ) and re-asserted by a
 * [RESYNC_PERIOD_TICKS] poll because vanilla resets the food level on respawn
 * and other plugins can write it. A write is skipped when the level is
 * unchanged since the last one, except on the poll, where the gateway compares
 * with what the client really has.
 *
 * This replaces the boss-bar weapon-resource HUD of ADR-0177 (WP2 / WP14).
 */
public class EnergyFoodBarListener(
    private val pools: IResourcePoolService,
    private val food: IPlayerFoodGateway,
    scheduler: IScheduler,
    resyncPeriodTicks: Long = RESYNC_PERIOD_TICKS,
) {
    private val joined: MutableSet<PlayerId> = linkedSetOf()
    private val lastLevel: MutableMap<PlayerId, Int> = mutableMapOf()

    private val task: IScheduler.TaskHandle =
        scheduler.runRepeatingTicks(resyncPeriodTicks, resyncPeriodTicks) { resyncAll() }

    public fun stop() {
        task.cancel()
    }

    public fun onJoined(event: PlayerJoinedServer) {
        joined += event.playerId
        sync(event.playerId, force = true)
    }

    public fun onQuit(event: PlayerQuitServer) {
        joined -= event.playerId
        lastLevel -= event.playerId
    }

    public fun onRespawned(event: PlayerRespawned) {
        sync(event.playerId, force = true)
    }

    public fun onSpent(event: ResourceSpent) {
        if (event.type == ResourceType.Energy) sync(event.playerId, force = false)
    }

    public fun onRegenned(event: ResourceRegenned) {
        if (event.type == ResourceType.Energy) sync(event.playerId, force = false)
    }

    public fun onActiveWeaponChanged(event: PlayerActiveWeaponChanged) {
        sync(event.playerId, force = false)
    }

    private fun resyncAll() {
        for (playerId in joined.toList()) sync(playerId, force = true)
    }

    private fun sync(playerId: PlayerId, force: Boolean) {
        if (playerId !in joined) return
        val pool = pools.currentPool(playerId) ?: return
        val max = pools.capFor(playerId, ResourceType.Energy).maxPool
        val level = EnergyFoodBar.foodLevelFor(pool.energy, max)
        if (!force && lastLevel[playerId] == level) return
        if (TRACE && level != EnergyFoodBar.MAX_FOOD_LEVEL) log.info("[weapons-debug] food sync {} energy={}/{} level={} force={}", playerId, pool.energy, max, level, force)
        when (val outcome = food.mirrorEnergy(playerId, level)) {
            is Result.Success -> lastLevel[playerId] = level
            is Result.Failure -> if (outcome.error is FoodMutationError.BackendUnavailable) {
                lastLevel.remove(playerId)
            }
        }
    }

    private val log = org.slf4j.LoggerFactory.getLogger(EnergyFoodBarListener::class.java)

    public companion object {
        private val TRACE: Boolean = System.getenv("ASTERA_DEBUG_WEAPONS") != null

        /** One second: faster than a player notices a stale bar after a respawn. */
        public const val RESYNC_PERIOD_TICKS: Long = 20L
    }
}
