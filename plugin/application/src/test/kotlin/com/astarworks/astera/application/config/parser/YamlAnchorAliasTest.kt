package com.astarworks.astera.application.config.parser

import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0016 §"Part 3" requires YAML anchor (`&name`) / alias (`*name`) to work
 * for file-local effect reuse. kaml is YAML 1.2 compliant and supports these
 * natively; this test pins that behaviour so a future kaml-version bump can
 * never silently regress it.
 */
class YamlAnchorAliasTest {

    @Serializable
    data class Particle(val id: String, val count: Int)

    @Serializable
    data class Effects(val particle: List<Particle>)

    @Serializable
    data class WeaponWithAnchors(
        val id: String,
        val effects: Effects,
    )

    private val parser = YamlContentParser()

    @Test
    fun `kaml resolves a single anchor referenced via alias`() {
        val yaml = """
            id: lightning-blade
            _spark: &spark { id: "vanilla:electric_spark", count: 30 }
            effects:
              particle:
                - *spark
        """.trimIndent()

        // kaml will not let unknown top-level keys through by default, so we
        // model `_spark` as an ignored field — the standard YAML-anchors
        // pattern is to keep a dedicated `_anchors` section. Test the
        // canonical shape next.
        @Serializable
        data class WithLeadingAnchor(
            val id: String,
            @kotlinx.serialization.SerialName("_spark") val ignoredAnchorHolder: Particle,
            val effects: Effects,
        )

        val result = YamlContentParser().parse(yaml, WithLeadingAnchor.serializer())

        assertThat(result.id).isEqualTo("lightning-blade")
        assertThat(result.effects.particle).hasSize(1)
        assertThat(result.effects.particle[0])
            .isEqualTo(Particle(id = "vanilla:electric_spark", count = 30))
        // Anchor and alias must resolve to the same logical value.
        assertThat(result.effects.particle[0]).isEqualTo(result.ignoredAnchorHolder)
    }

    @Test
    fun `kaml resolves multiple aliases of the same anchor`() {
        // The canonical ADR-0016 shape: a single anchor reused several times.
        @Serializable
        data class WithAnchorHolder(
            @kotlinx.serialization.SerialName("_spark") val spark: Particle,
            val effects: Effects,
        )

        val yaml = """
            _spark: &spark
              id: vanilla:spark
              count: 30
            effects:
              particle:
                - *spark
                - *spark
                - *spark
        """.trimIndent()

        val result = YamlContentParser().parse(yaml, WithAnchorHolder.serializer())

        assertThat(result.effects.particle)
            .hasSize(3)
            .allMatch { it == Particle("vanilla:spark", 30) }
    }
}
