package com.astarworks.astera.core.domain.mob

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SpawnConditionsTest {

    private val forestNoon = SpawnContext(
        biomeKey = "minecraft:forest",
        lightLevel = 14,
        worldTimeTicks = 6000L,
    )

    private val caveMidnight = SpawnContext(
        biomeKey = "minecraft:dripstone_caves",
        lightLevel = 2,
        worldTimeTicks = 18_000L,
    )

    @Test
    fun `ANY matches every context`() {
        assertThat(SpawnConditions.ANY.matches(forestNoon)).isTrue()
        assertThat(SpawnConditions.ANY.matches(caveMidnight)).isTrue()
    }

    @Test
    fun `biome filter gates on set membership`() {
        val forestOnly = SpawnConditions(biomeKeys = setOf("minecraft:forest"))
        assertThat(forestOnly.matches(forestNoon)).isTrue()
        assertThat(forestOnly.matches(caveMidnight)).isFalse()
    }

    @Test
    fun `light level filter is inclusive on both bounds`() {
        val dark = SpawnConditions(minLightLevel = 0, maxLightLevel = 7)
        assertThat(dark.matches(forestNoon)).isFalse() // light 14 > 7
        assertThat(dark.matches(caveMidnight)).isTrue() // light 2 in [0,7]
    }

    @Test
    fun `time-of-day filter is inclusive on both ends`() {
        val daytime = SpawnConditions(timeOfDayRange = 0L..14000L)
        assertThat(daytime.matches(forestNoon)).isTrue() // 6000 in range
        assertThat(daytime.matches(caveMidnight)).isFalse() // 18000 outside
    }

    @Test
    fun `region filter gates on equality`() {
        val region = MobRegionId("forest-edge")
        val ctxInRegion = forestNoon.copy(regionId = region)
        val ctxNoRegion = forestNoon.copy(regionId = null)
        val gated = SpawnConditions(regionId = region)
        assertThat(gated.matches(ctxInRegion)).isTrue()
        assertThat(gated.matches(ctxNoRegion)).isFalse()
    }

    @Test
    fun `multiple filters AND-combine`() {
        val strict = SpawnConditions(
            biomeKeys = setOf("minecraft:forest"),
            timeOfDayRange = 0L..14000L,
            minLightLevel = 10,
        )
        assertThat(strict.matches(forestNoon)).isTrue()

        // Biome wrong → fails
        assertThat(strict.matches(forestNoon.copy(biomeKey = "minecraft:desert"))).isFalse()
        // Time wrong → fails
        assertThat(strict.matches(forestNoon.copy(worldTimeTicks = 20_000L))).isFalse()
        // Light too low → fails
        assertThat(strict.matches(forestNoon.copy(lightLevel = 5))).isFalse()
    }

    @Test
    fun `construction rejects min greater than max light level`() {
        assertThatThrownBy {
            SpawnConditions(minLightLevel = 10, maxLightLevel = 5)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `construction rejects out-of-range time bounds`() {
        assertThatThrownBy {
            SpawnConditions(timeOfDayRange = -1L..14000L)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            SpawnConditions(timeOfDayRange = 0L..25_000L)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `SpawnContext rejects invalid light level and time`() {
        assertThatThrownBy {
            SpawnContext(biomeKey = "x", lightLevel = -1, worldTimeTicks = 0L)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            SpawnContext(biomeKey = "x", lightLevel = 16, worldTimeTicks = 0L)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            SpawnContext(biomeKey = "x", lightLevel = 0, worldTimeTicks = 30_000L)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
