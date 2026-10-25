package com.astarworks.astera.core.application.service.match

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchConfigService
import com.astarworks.astera.core.application.port.outbound.IWorldRulesGateway
import com.astarworks.astera.core.application.port.outbound.IWorldTimeGateway
import com.astarworks.astera.core.application.port.outbound.IWorldWeatherGateway
import com.astarworks.astera.core.application.port.outbound.MatchConfigError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchConfigApplied
import com.astarworks.astera.core.domain.match.MatchConfig
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.world.WorldId
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [IMatchConfigService] implementation.
 *
 * Lives in `application/service` (no Bukkit, no JDBC) so the same impl
 * serves the in-memory bootstrap and the per-match overlay logic regardless
 * of whether the world gateways underneath are in-memory or Paper-backed
 * (priority-100 overlay).
 *
 * Wave 5n (ADR-0080).
 */
public class InMemoryMatchConfigService(
    private val worldRules: IWorldRulesGateway,
    private val worldTime: IWorldTimeGateway,
    private val worldWeather: IWorldWeatherGateway,
    private val eventBus: IDomainEventBus,
    private val tickClock: () -> Long = { 0L },
) : IMatchConfigService {

    /** Snapshot of (matchId, worldId, applied config) for resetToDefault. */
    private data class ActiveOverlay(val worldId: WorldId, val config: MatchConfig)

    private val active: MutableMap<MatchId, ActiveOverlay> = ConcurrentHashMap()

    override fun configOf(matchId: MatchId): MatchConfig =
        active[matchId]?.config ?: MatchConfig.DEFAULT

    override fun apply(
        matchId: MatchId,
        worldId: WorldId,
        config: MatchConfig,
    ): Result<Unit, MatchConfigError> {
        // Apply each axis if the config sets it (null = leave alone).
        config.pvpEnabled?.let { worldRules.setPvP(worldId, it) }
        config.difficulty?.let { worldRules.setDifficulty(worldId, it) }
        worldRules.setKeepInventoryOnDeath(worldId, config.keepInventoryOnDeath)

        config.timeMode?.let { tick ->
            worldTime.setTime(worldId, tick)
            worldTime.freezeTime(worldId, true)
            worldRules.setDoDaylightCycle(worldId, false)
        }
        if (config.timeMode == null) {
            // Restore daylight cycle if the previous overlay had locked it.
            worldRules.setDoDaylightCycle(worldId, true)
            worldTime.freezeTime(worldId, false)
        }

        config.weatherMode?.let { weather ->
            worldWeather.setWeather(worldId, weather)
            worldWeather.freezeWeather(worldId, true)
            worldRules.setDoWeatherCycle(worldId, false)
        }
        if (config.weatherMode == null) {
            worldRules.setDoWeatherCycle(worldId, true)
            worldWeather.freezeWeather(worldId, false)
        }

        active[matchId] = ActiveOverlay(worldId, config)

        eventBus.publish(
            MatchConfigApplied(
                matchId = matchId,
                worldId = worldId,
                config = config,
                atTick = tickClock(),
            ),
        )

        return Result.success(Unit)
    }

    override fun resetToDefault(matchId: MatchId): MatchConfig {
        val overlay = active.remove(matchId) ?: return MatchConfig.DEFAULT
        val world = overlay.worldId

        // Reset to vanilla.
        worldRules.setPvP(world, true)
        worldRules.setKeepInventoryOnDeath(world, false)
        worldRules.setDoDaylightCycle(world, true)
        worldRules.setDoWeatherCycle(world, true)
        worldTime.freezeTime(world, false)
        worldWeather.freezeWeather(world, false)

        return overlay.config
    }
}
