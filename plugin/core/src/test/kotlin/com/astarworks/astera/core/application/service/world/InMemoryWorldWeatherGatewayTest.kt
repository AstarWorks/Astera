package com.astarworks.astera.core.application.service.world

import com.astarworks.astera.core.domain.world.WeatherState
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InMemoryWorldWeatherGatewayTest {

    private val arena = WorldId("arena")

    @Test
    fun `getCurrentWeather returns null for unknown world`() {
        val gw = InMemoryWorldWeatherGateway()
        assertThat(gw.getCurrentWeather(arena)).isNull()
    }

    @Test
    fun `setWeather then getCurrentWeather returns the state`() {
        val gw = InMemoryWorldWeatherGateway()
        gw.setWeather(arena, WeatherState.Rain(durationTicks = 6000))
        assertThat(gw.getCurrentWeather(arena)).isEqualTo(WeatherState.Rain(6000))

        gw.setWeather(arena, WeatherState.Clear)
        assertThat(gw.getCurrentWeather(arena)).isEqualTo(WeatherState.Clear)

        gw.setWeather(arena, WeatherState.Thunder(durationTicks = 12000))
        assertThat(gw.getCurrentWeather(arena)).isEqualTo(WeatherState.Thunder(12000))
    }

    @Test
    fun `freezeWeather toggles state`() {
        val gw = InMemoryWorldWeatherGateway()
        assertThat(gw.isWeatherFrozen(arena)).isFalse()

        gw.freezeWeather(arena, true)
        assertThat(gw.isWeatherFrozen(arena)).isTrue()

        gw.freezeWeather(arena, false)
        assertThat(gw.isWeatherFrozen(arena)).isFalse()
    }

    @Test
    fun `freezeWeather initialises currentWeather to Clear if never set`() {
        val gw = InMemoryWorldWeatherGateway()
        gw.freezeWeather(arena, true)
        assertThat(gw.getCurrentWeather(arena)).isEqualTo(WeatherState.Clear)
    }
}
