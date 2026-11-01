package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ArenaWorldWeatherE2eTest : E2eBase() {
    private fun assertClear() {
        val answer = rcon("""execute in minecraft:overworld if predicate {"condition":"minecraft:weather_check","raining":false,"thundering":false} run time query gametime""")
        assertThat(answer).describedAs("arena world must have neither rain nor thunder").contains("The time is")
    }

    @Test
    fun `arena world boots with frozen clear weather and rejects rain and thunder`() {
        E2eReport.check("ArenaWeather", "arena world weather cycle is off and boot weather is clear", "RCON gamerule + weather_check predicate") {
            // Paper 26.3 calls doWeatherCycle advance_weather in commands.
            val cycle = rcon("execute in minecraft:overworld run gamerule minecraft:advance_weather")
            assertThat(cycle.trimEnd()).describedAs("arena weather cycle: $cycle").endsWith("false")
            assertClear()
            "advance_weather=false; no rain or thunder"
        }
        E2eReport.check("ArenaWeather", "explicit rain and thunder cannot re-wet arena farmland", "RCON weather + weather_check predicate") {
            try {
                for (weather in listOf("rain", "thunder")) {
                    rcon("execute in minecraft:overworld run weather $weather")
                    assertClear()
                }
            } finally {
                rcon("execute in minecraft:overworld run weather clear")
            }
            "rain and thunder changes rejected"
        }
    }
}
