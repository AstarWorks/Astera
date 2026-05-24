package com.astarworks.astera.match.application.config.gamemode.annihilation

import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0108 Wave 5annihilation — YAML overlay parse tests.
 */
class AnnihilationYamlOverlayTest {

    @Test
    fun `empty map yields defaults`() {
        val result = AnnihilationYamlOverlay.parse(emptyMap())
        assertThat(result.config).isEqualTo(AnnihilationConfig.DEFAULT)
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun `valid overrides land in the typed config`() {
        val raw = mapOf(
            "pillar_hp" to "750",
            "build_phase_ticks" to "4000",
            "engage_phase_ticks" to "8000",
            "tier3_phase_ticks" to "4000",
            "sudden_death_ticks" to "600",
            "sudden_death_dot_per_tick" to "8",
            "respawn_delay_seconds_tier1_lost" to "4",
            "resource_regen_mult_tier2_lost" to "0.25",
        )
        val result = AnnihilationYamlOverlay.parse(raw)
        assertThat(result.warnings).isEmpty()
        assertThat(result.config.pillarHp).isEqualTo(750)
        assertThat(result.config.buildPhaseTicks).isEqualTo(4000L)
        assertThat(result.config.engagePhaseTicks).isEqualTo(8000L)
        assertThat(result.config.tier3PhaseTicks).isEqualTo(4000L)
        assertThat(result.config.suddenDeathTicks).isEqualTo(600L)
        assertThat(result.config.suddenDeathDotPerTick).isEqualTo(8)
        assertThat(result.config.respawnDelaySecondsTier1Lost).isEqualTo(4)
        assertThat(result.config.resourceRegenMultTier2Lost).isEqualTo(0.25)
    }

    @Test
    fun `unparseable value warns and uses default`() {
        val raw = mapOf("pillar_hp" to "not-an-int")
        val result = AnnihilationYamlOverlay.parse(raw)
        assertThat(result.config.pillarHp).isEqualTo(AnnihilationConfig.DEFAULT.pillarHp)
        assertThat(result.warnings).hasSize(1)
        assertThat(result.warnings[0]).contains("pillar_hp")
    }

    @Test
    fun `domain-rejected overlay warns and falls back to defaults`() {
        // resource_regen_mult_tier2_lost must be 0..1; supplying 2.0 will throw.
        val raw = mapOf("resource_regen_mult_tier2_lost" to "2.0")
        val result = AnnihilationYamlOverlay.parse(raw)
        assertThat(result.config).isEqualTo(AnnihilationConfig.DEFAULT)
        assertThat(result.warnings).anyMatch { it.contains("rejected by domain validation") }
    }

    @Test
    fun `unknown keys are silently ignored`() {
        val raw = mapOf("unknown_key" to "value")
        val result = AnnihilationYamlOverlay.parse(raw)
        assertThat(result.config).isEqualTo(AnnihilationConfig.DEFAULT)
        assertThat(result.warnings).isEmpty()
    }
}
