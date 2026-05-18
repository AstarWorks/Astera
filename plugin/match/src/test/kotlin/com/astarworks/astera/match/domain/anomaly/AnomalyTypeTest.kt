package com.astarworks.astera.match.domain.anomaly

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Wave 5c [AnomalyType] sanity tests.
 *
 * The sealed hierarchy is intentionally narrow: 6 `data object` variants.
 * These tests pin (a) that all six variants exist and (b) that the i18n
 * keys are distinct (typo-detection — without this a copy-paste of one
 * variant's MessageKey to another would silently break i18n parity until
 * an integration test caught it).
 */
internal class AnomalyTypeTest {

    @Test
    fun `each variant carries a distinct display name key`() {
        val keys = listOf(
            AnomalyType.ResourceSpike.displayNameKey,
            AnomalyType.StatusBurst.displayNameKey,
            AnomalyType.SpawnSurge.displayNameKey,
            AnomalyType.TimeWarp.displayNameKey,
            AnomalyType.GravityShift.displayNameKey,
            AnomalyType.FogOfWar.displayNameKey,
        )
        assertThat(keys).doesNotHaveDuplicates()
        assertThat(keys).hasSize(6)
    }

    @Test
    fun `display name keys use the astera anomaly namespace`() {
        val keys = listOf(
            AnomalyType.ResourceSpike,
            AnomalyType.StatusBurst,
            AnomalyType.SpawnSurge,
            AnomalyType.TimeWarp,
            AnomalyType.GravityShift,
            AnomalyType.FogOfWar,
        ).map { it.displayNameKey.value }

        assertThat(keys).allSatisfy { key ->
            assertThat(key).startsWith("astera.anomaly.")
        }
    }

    @Test
    fun `exhaustive when over every variant compiles and reaches every branch`() {
        // The compiler enforces exhaustiveness; this test exercises every
        // branch at runtime so coverage tools see all six paths.
        val types: List<AnomalyType> = listOf(
            AnomalyType.ResourceSpike,
            AnomalyType.StatusBurst,
            AnomalyType.SpawnSurge,
            AnomalyType.TimeWarp,
            AnomalyType.GravityShift,
            AnomalyType.FogOfWar,
        )
        val labels: List<String> = types.map { t ->
            when (t) {
                AnomalyType.ResourceSpike -> "resource-spike"
                AnomalyType.StatusBurst -> "status-burst"
                AnomalyType.SpawnSurge -> "spawn-surge"
                AnomalyType.TimeWarp -> "time-warp"
                AnomalyType.GravityShift -> "gravity-shift"
                AnomalyType.FogOfWar -> "fog-of-war"
            }
        }
        assertThat(labels).containsExactly(
            "resource-spike",
            "status-burst",
            "spawn-surge",
            "time-warp",
            "gravity-shift",
            "fog-of-war",
        )
    }

    @Test
    fun `variants are referentially equal (data object singletons)`() {
        // data object means each variant is a true singleton; this is a
        // sanity check protecting against an accidental `class` declaration.
        assertThat(AnomalyType.ResourceSpike).isSameAs(AnomalyType.ResourceSpike)
        assertThat(AnomalyType.StatusBurst).isSameAs(AnomalyType.StatusBurst)
    }
}
