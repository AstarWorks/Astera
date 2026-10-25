package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.world.WeatherState
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: control the weather of a Bukkit world.
 *
 * Phrased in [WeatherState] (sealed: `Clear` / `Rain(durationTicks)` /
 * `Thunder(durationTicks)`) rather than Bukkit's boolean `storm` /
 * `thundering` flags so use cases reason about a single value.
 *
 * The Paper adapter (Wave 6b) maps to `World.setStorm` / `setThundering` /
 * `setWeatherDuration` / `setThunderDuration`. The in-memory impl tracks
 * state via a `MutableMap<WorldId, WeatherState>` so per-match config
 * overlay tests can assert weather lock without a Paper server.
 *
 * Wave 5n (ADR-0092).
 */
public interface IWorldWeatherGateway {

    /**
     * Set the weather of [world] to [weather]. Has no effect if the world
     * is unknown. The duration encoded in [WeatherState.Rain] /
     * [WeatherState.Thunder] is honoured by the Paper impl; in the in-
     * memory impl it is preserved as-is for test assertions.
     */
    public fun setWeather(world: WorldId, weather: WeatherState)

    /**
     * Freeze (or unfreeze) the weather of [world]. When frozen, vanilla
     * weather-cycle advancement stops; the most recent [setWeather] call's
     * state stays in effect.
     */
    public fun freezeWeather(world: WorldId, frozen: Boolean)

    /**
     * Returns the current weather of [world], or `null` if the world is
     * unknown.
     */
    public fun getCurrentWeather(world: WorldId): WeatherState?

    /**
     * Returns `true` if weather is currently frozen for [world]. Returns
     * `false` when the world is unknown.
     */
    public fun isWeatherFrozen(world: WorldId): Boolean
}
