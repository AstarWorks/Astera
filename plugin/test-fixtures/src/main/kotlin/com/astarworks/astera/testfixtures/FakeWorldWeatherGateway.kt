package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IWorldWeatherGateway
import com.astarworks.astera.core.application.service.world.InMemoryWorldWeatherGateway
import com.astarworks.astera.core.domain.world.WeatherState
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Cross-plugin Fake for [IWorldWeatherGateway]. Wraps the in-memory impl
 * and exposes recorded `setWeather` / `freezeWeather` calls for assertions.
 *
 * Wave 5n (ADR-0080).
 */
public class FakeWorldWeatherGateway : IWorldWeatherGateway {

    public data class SetWeatherCall(val world: WorldId, val weather: WeatherState)
    public data class FreezeCall(val world: WorldId, val frozen: Boolean)

    private val delegate: InMemoryWorldWeatherGateway = InMemoryWorldWeatherGateway()

    public val setWeatherCalls: MutableList<SetWeatherCall> = mutableListOf()
    public val freezeCalls: MutableList<FreezeCall> = mutableListOf()

    override fun setWeather(world: WorldId, weather: WeatherState) {
        setWeatherCalls += SetWeatherCall(world, weather)
        delegate.setWeather(world, weather)
    }

    override fun freezeWeather(world: WorldId, frozen: Boolean) {
        freezeCalls += FreezeCall(world, frozen)
        delegate.freezeWeather(world, frozen)
    }

    override fun getCurrentWeather(world: WorldId): WeatherState? = delegate.getCurrentWeather(world)

    override fun isWeatherFrozen(world: WorldId): Boolean = delegate.isWeatherFrozen(world)
}
