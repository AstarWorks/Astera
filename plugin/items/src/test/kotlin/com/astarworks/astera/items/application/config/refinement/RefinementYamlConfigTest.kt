package com.astarworks.astera.items.application.config.refinement

import com.astarworks.astera.items.domain.refinement.SubStatKey
import com.charleskorn.kaml.Yaml
import kotlinx.serialization.decodeFromString
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RefinementYamlConfigTest {

    private val yaml = Yaml.default

    @Test
    fun `full yaml parses to enabled spec with every axis`() {
        val src = """
            id: example-sword
            profile:
              enabled: true
              gemstone_slots: 3
              reforge_pool: [sharp, fierce, demonic]
              upgradeable_to: 10
              sub_stat_count: 4
              max_rolls_per_stat: 5
              candidate_stats: [attack, crit_chance, crit_dmg, defense]
        """.trimIndent()
        val parsed: RefinementProfileYaml = yaml.decodeFromString(src)
        assertThat(parsed.id).isEqualTo("example-sword")
        val spec = parsed.profile.toDomain()
        assertThat(spec.enabled).isTrue()
        assertThat(spec.gemstoneSlots).isEqualTo(3)
        assertThat(spec.reforgePool).containsExactly("sharp", "fierce", "demonic")
        assertThat(spec.upgradeableTo).isEqualTo(10)
        assertThat(spec.subStatCount).isEqualTo(4)
        assertThat(spec.maxRollsPerStat).isEqualTo(5)
        assertThat(spec.candidateSubStats).containsExactlyInAnyOrder(
            SubStatKey.ATTACK,
            SubStatKey.CRIT_CHANCE,
            SubStatKey.CRIT_DAMAGE,
            SubStatKey.DEFENSE,
        )
    }

    @Test
    fun `unknown candidate sub-stat throws IllegalArgumentException`() {
        val cfg = RefinementYamlConfig(
            enabled = true,
            subStatCount = 1,
            candidateStats = listOf("not_a_real_stat"),
        )
        assertThatThrownBy { cfg.toDomain() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unknown sub-stat")
    }

    @Test
    fun `DISABLED roundtrips to empty domain spec`() {
        val spec = RefinementYamlConfig.DISABLED.toDomain()
        assertThat(spec.enabled).isFalse()
        assertThat(spec.anyAxisEnabled).isFalse()
    }
}
