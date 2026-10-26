package com.astarworks.astera.core.adapter.paper.world

import com.astarworks.astera.core.application.port.outbound.IWorldWeatherGateway
import com.astarworks.astera.core.domain.world.WeatherState
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.GameRule
import org.bukkit.Server
import org.bukkit.World
import org.slf4j.LoggerFactory

/**
 * Paper concrete of [IWorldWeatherGateway] (Wave 6d, ADR-0161). Wraps Bukkit
 * `World.setStorm` / `setThundering` / `setWeatherDuration` /
 * `setThunderDuration` API 1:1 onto the vendor-neutral port — overlays
 * [com.astarworks.astera.core.application.service.world.InMemoryWorldWeatherGateway]
 * at priority 100 once registered in `AsteraCorePlugin.onEnable`.
 *
 * # Bukkit mapping (WeatherState → storm + thunder flags)
 *
 *  - [WeatherState.Clear] → `setStorm(false)`, `setThundering(false)`
 *  - [WeatherState.Rain] → `setStorm(true)`, `setThundering(false)`,
 *    `setWeatherDuration(durationTicks)`. `durationTicks == 0` is treated as
 *    "indefinite" — Bukkit accepts `0` as "no scheduled change" so we skip
 *    the duration setter in that case.
 *  - [WeatherState.Thunder] → `setStorm(true)`, `setThundering(true)`,
 *    `setThunderDuration(durationTicks)`. Same `0`-means-indefinite rule as
 *    Rain (we also set the weather duration to keep storm + thunder aligned).
 *  - [freezeWeather] → `setGameRule(GameRule.DO_WEATHER_CYCLE, !frozen)`.
 *  - [getCurrentWeather] → reads `world.isThundering` + `world.hasStorm` and
 *    rebuilds the sealed [WeatherState]. The remaining duration is read from
 *    `world.weatherDuration` / `world.thunderDuration` so callers can see the
 *    server's actual schedule.
 *  - [isWeatherFrozen] → `!world.getGameRuleValue(GameRule.DO_WEATHER_CYCLE)`.
 *
 * # ADR-0149 §C invariant
 *
 * N/A — this gateway never touches player HP, scoreboard, or damage events.
 * It only mutates Bukkit world-level weather state.
 *
 * # Main-thread requirement
 *
 * All Bukkit weather / gamerule mutations require the server main thread
 * (Folia regional contract per ADR-0144 / ADR-0145 / ADR-0146). The port
 * has no `Result` return, so off-main calls log WARN and drop — matching
 * the InMemory SSOT's "unknown world → no-op" semantics.
 *
 * # Offline / unloaded world semantics
 *
 *  - Unknown world ([Server.getWorld] returns `null`): unit-returning methods
 *    log DEBUG and drop; [getCurrentWeather] returns `null`; [isWeatherFrozen]
 *    returns `false` per port contract.
 *
 * # Registration
 *
 * Wire in `AsteraCorePlugin.onEnable` at priority 100 (overlays
 * `InMemoryWorldWeatherGateway` priority 0).
 */
public class PaperWorldWeatherGateway(
    private val server: Server,
) : IWorldWeatherGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun setWeather(world: WorldId, weather: WeatherState) {
        if (!org.bukkit.Bukkit.isGlobalTickThread()) {
            log.warn(
                "PaperWorldWeatherGateway.setWeather called off main-thread (world={}, weather={}); ignored",
                world,
                weather,
            )
            return
        }
        val w = server.getWorld(world.value) ?: run {
            log.debug("PaperWorldWeatherGateway.setWeather: world {} not loaded", world)
            return
        }
        when (weather) {
            is WeatherState.Clear -> {
                w.isThundering = false
                w.setStorm(false)
            }
            is WeatherState.Rain -> {
                w.isThundering = false
                w.setStorm(true)
                if (weather.durationTicks > 0) {
                    w.weatherDuration = weather.durationTicks
                }
            }
            is WeatherState.Thunder -> {
                w.setStorm(true)
                w.isThundering = true
                if (weather.durationTicks > 0) {
                    w.weatherDuration = weather.durationTicks
                    w.thunderDuration = weather.durationTicks
                }
            }
        }
    }

    override fun freezeWeather(world: WorldId, frozen: Boolean) {
        if (!org.bukkit.Bukkit.isGlobalTickThread()) {
            log.warn(
                "PaperWorldWeatherGateway.freezeWeather called off main-thread (world={}, frozen={}); ignored",
                world,
                frozen,
            )
            return
        }
        val w = server.getWorld(world.value) ?: run {
            log.debug("PaperWorldWeatherGateway.freezeWeather: world {} not loaded", world)
            return
        }
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, !frozen)
    }

    override fun getCurrentWeather(world: WorldId): WeatherState? {
        val w = server.getWorld(world.value) ?: return null
        return readWeather(w)
    }

    override fun isWeatherFrozen(world: WorldId): Boolean {
        val w = server.getWorld(world.value) ?: return false
        return w.getGameRuleValue(GameRule.DO_WEATHER_CYCLE)?.not() ?: false
    }

    /**
     * Map a Bukkit [World]'s storm + thunder + duration trio back to the
     * sealed [WeatherState]. Thunder takes priority (thundering implies
     * storm); pure storm is Rain; neither is Clear.
     */
    private fun readWeather(w: World): WeatherState = when {
        w.isThundering -> WeatherState.Thunder(w.thunderDuration.coerceAtLeast(0))
        w.hasStorm() -> WeatherState.Rain(w.weatherDuration.coerceAtLeast(0))
        else -> WeatherState.Clear
    }
}
