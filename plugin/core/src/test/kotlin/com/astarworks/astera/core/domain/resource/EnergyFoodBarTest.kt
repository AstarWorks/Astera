package com.astarworks.astera.core.domain.resource

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** F2-2 (ADR-0194): energy -> vanilla food level mapping. */
class EnergyFoodBarTest {

    @Test
    fun `full and empty are exact`() {
        assertThat(EnergyFoodBar.foodLevelFor(100, 100)).isEqualTo(20)
        assertThat(EnergyFoodBar.foodLevelFor(0, 100)).isEqualTo(0)
    }

    @Test
    fun `half is ten`() {
        assertThat(EnergyFoodBar.foodLevelFor(50, 100)).isEqualTo(10)
    }

    @Test
    fun `partial energy never reads empty or full`() {
        assertThat(EnergyFoodBar.foodLevelFor(1, 100)).isEqualTo(1)
        assertThat(EnergyFoodBar.foodLevelFor(99, 100)).isEqualTo(19)
    }

    @Test
    fun `degenerate caps map to empty and over-full is clamped`() {
        assertThat(EnergyFoodBar.foodLevelFor(10, 0)).isEqualTo(0)
        assertThat(EnergyFoodBar.foodLevelFor(150, 100)).isEqualTo(20)
        assertThat(EnergyFoodBar.foodLevelFor(-5, 100)).isEqualTo(0)
    }

    @Test
    fun `the level is monotonic in energy`() {
        val levels = (0..100).map { EnergyFoodBar.foodLevelFor(it, 100) }
        assertThat(levels).isSorted()
    }

    @Test
    fun `vanilla allows a sprint start only above the documented threshold`() {
        val firstSprintable = (0..100).first { EnergyFoodBar.foodLevelFor(it, 100) > EnergyFoodBar.SPRINT_MIN_EXCLUSIVE }
        assertThat(firstSprintable / 100.0).isBetween(EnergyFoodBar.SPRINT_THRESHOLD_RATIO, EnergyFoodBar.SPRINT_THRESHOLD_RATIO + 0.05)
    }
}
