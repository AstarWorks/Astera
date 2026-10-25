package com.astarworks.astera.core.application.service.world

import com.astarworks.astera.core.application.port.outbound.IWorldWeatherGateway
import com.astarworks.astera.core.domain.world.WeatherState
import com.astarworks.astera.core.domain.world.WorldId

/**
 * In-memory [IWorldWeatherGateway] used at priority 0 until the Paper adapter
 * (Wave 6b) overlays at priority 100.
 *
 * Tracks per-world weather and freeze state in `ConcurrentHashMap`s so unit
 * tests can assert side-effects without a Paper server.
 *
 * Wave 5n (ADR-0080).
 */
public class InMemoryWorldWeatherGateway : IWorldWeatherGateway {

    private val weather: MutableMap<WorldId, WeatherState> = java.util.concurrent.ConcurrentHashMap()
    private val frozen: MutableSet<WorldId> = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap()
    )

    override fun setWeather(world: WorldId, weather: WeatherState) {
        this.weather[world] = weather
    }

    override fun freezeWeather(world: WorldId, frozen: Boolean) {
        if (frozen) this.frozen += world else this.frozen -= world
        weather.putIfAbsent(world, WeatherState.Clear)
    }

    override fun getCurrentWeather(world: WorldId): WeatherState? = weather[world]

    override fun isWeatherFrozen(world: WorldId): Boolean = world in frozen
}
