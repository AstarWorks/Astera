package com.astarworks.astera.match.application.config.gamemode.annihilation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * ADR-0108 Wave 5annihilation — Nexus template YAML validation tests.
 */
class NexusTemplateYamlConfigTest {

    private fun validTier(t: Int, hpOverride: Int? = null) = NexusTierYamlConfig(
        tier = t,
        blockMaterial = "minecraft:obsidian",
        offset = OffsetYamlConfig(x = t, y = 1, z = 0),
        hpOverride = hpOverride,
    )

    @Test
    fun `valid 3-tier template parses cleanly`() {
        val yaml = NexusTemplateYamlConfig(
            id = "default-3pillar",
            nameKey = "astera.gamemode.annihilation.template.default",
            tiers = listOf(validTier(1), validTier(2), validTier(3, hpOverride = 800)),
        )
        val spec = yaml.toSpec()
        assertThat(spec.id).isEqualTo("default-3pillar")
        assertThat(spec.tiers).hasSize(3)
        assertThat(spec.tiers.map { it.tier }).containsExactly(1, 2, 3)
        assertThat(spec.tiers[2].hpOverride).isEqualTo(800)
    }

    @Test
    fun `missing tier rejected`() {
        val yaml = NexusTemplateYamlConfig(
            id = "broken",
            nameKey = "k",
            tiers = listOf(validTier(1), validTier(2)),
        )
        assertThatThrownBy { yaml.toSpec() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must declare exactly 3 tiers")
    }

    @Test
    fun `duplicate tier rejected`() {
        val yaml = NexusTemplateYamlConfig(
            id = "broken",
            nameKey = "k",
            tiers = listOf(validTier(1), validTier(1), validTier(3)),
        )
        assertThatThrownBy { yaml.toSpec() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `invalid tier number rejected`() {
        assertThatThrownBy { validTier(4) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be 1, 2, or 3")
    }

    @Test
    fun `blank block material rejected`() {
        assertThatThrownBy {
            NexusTierYamlConfig(tier = 1, blockMaterial = "", offset = OffsetYamlConfig(0, 0, 0))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("block_material")
    }

    @Test
    fun `non-positive hp override rejected`() {
        assertThatThrownBy {
            NexusTierYamlConfig(
                tier = 1, blockMaterial = "minecraft:obsidian",
                offset = OffsetYamlConfig(0, 0, 0), hpOverride = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
