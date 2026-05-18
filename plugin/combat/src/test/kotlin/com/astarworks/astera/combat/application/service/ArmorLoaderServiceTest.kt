package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.domain.armor.ArmorId
import com.astarworks.astera.combat.domain.armor.ArmorSetKey
import com.astarworks.astera.combat.domain.armor.ArmorSlot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end test for [ArmorLoaderService] — mirrors [SkillLoaderServiceTest]:
 * happy path piece + set, malformed isolation, missing-directory soft fallback.
 */
class ArmorLoaderServiceTest {

    private val pieceYaml = """
        type: piece
        id: test-helmet
        display_name_key: astera.armor.test-helmet.name
        slot: helmet
        damage_reduction: 0.10
    """.trimIndent()

    private val pieceWithSetYaml = """
        type: piece
        id: test-chestplate
        display_name_key: astera.armor.test-chestplate.name
        slot: chestplate
        damage_reduction: 0.25
        set_key: test-set
    """.trimIndent()

    private val setYaml = """
        type: set
        key: test-set
        display_name_key: astera.armor.set.test-set.name
        pieces:
          - test-helmet
          - test-chestplate
          - test-leggings
          - test-boots
        two_piece_bonus: 0.05
        four_piece_bonus: 0.20
    """.trimIndent()

    @Test
    fun `happy path loads a piece`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-helmet.yaml"), pieceYaml)

        val registry = MutableArmorRegistry()
        val report = ArmorLoaderService(registry).loadFrom(dir)

        assertThat(report).isEqualTo(ArmorLoaderService.LoadReport(loaded = 1, failed = 0))
        val spec = registry.find(ArmorId("test-helmet"))
        assertThat(spec).isNotNull
        requireNotNull(spec)
        assertThat(spec.slot).isEqualTo(ArmorSlot.HELMET)
        assertThat(spec.reduction.default).isEqualTo(0.10)
        assertThat(spec.reduction.perAttribute).isEmpty()
        assertThat(spec.setKey).isNull()
    }

    @Test
    fun `per-attribute reduction overrides parse correctly`(@TempDir dir: Path) {
        val perAttrYaml = """
            type: piece
            id: fire-chest
            display_name_key: astera.armor.fire-chest.name
            slot: chestplate
            damage_reduction: 0.20
            damage_reduction_attributes:
              fire: 0.60
              physical: 0.10
        """.trimIndent()
        Files.writeString(dir.resolve("fire-chest.yaml"), perAttrYaml)

        val registry = MutableArmorRegistry()
        val report = ArmorLoaderService(registry).loadFrom(dir)

        assertThat(report.loaded).isEqualTo(1)
        val spec = registry.find(ArmorId("fire-chest"))
        requireNotNull(spec)
        assertThat(spec.reduction.default).isEqualTo(0.20)
        assertThat(spec.reduction.forAttribute(
            com.astarworks.astera.core.domain.damage.DamageAttribute.FIRE,
        )).isEqualTo(0.60)
        assertThat(spec.reduction.forAttribute(
            com.astarworks.astera.core.domain.damage.DamageAttribute.PHYSICAL,
        )).isEqualTo(0.10)
        assertThat(spec.reduction.forAttribute(
            com.astarworks.astera.core.domain.damage.DamageAttribute.ELECTRIC,
        )).isEqualTo(0.20)  // fallback to default
    }

    @Test
    fun `happy path loads a set definition`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-set.yaml"), setYaml)

        val registry = MutableArmorRegistry()
        val report = ArmorLoaderService(registry).loadFrom(dir)

        assertThat(report).isEqualTo(ArmorLoaderService.LoadReport(loaded = 1, failed = 0))
        val set = registry.findSet(ArmorSetKey("test-set"))
        assertThat(set).isNotNull
        requireNotNull(set)
        assertThat(set.twoPieceBonus).isEqualTo(0.05)
        assertThat(set.fourPieceBonus).isEqualTo(0.20)
        assertThat(set.pieces).hasSize(4)
    }

    @Test
    fun `piece with set_key wires both registries`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("piece.yaml"), pieceWithSetYaml)
        Files.writeString(dir.resolve("set.yaml"), setYaml)

        val registry = MutableArmorRegistry()
        val report = ArmorLoaderService(registry).loadFrom(dir)

        assertThat(report).isEqualTo(ArmorLoaderService.LoadReport(loaded = 2, failed = 0))
        val piece = registry.find(ArmorId("test-chestplate"))
        assertThat(piece).isNotNull
        assertThat(piece!!.setKey).isEqualTo(ArmorSetKey("test-set"))
        assertThat(registry.findSet(ArmorSetKey("test-set"))).isNotNull
    }

    @Test
    fun `malformed yaml is skipped and other files still load`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("good.yaml"), pieceYaml)
        Files.writeString(dir.resolve("broken.yaml"), "::: not valid : yaml :::\n  - oops")

        val registry = MutableArmorRegistry()
        val report = ArmorLoaderService(registry).loadFrom(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(registry.find(ArmorId("test-helmet"))).isNotNull
    }

    @Test
    fun `missing directory returns zero counts`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist")
        val registry = MutableArmorRegistry()

        val report = ArmorLoaderService(registry).loadFrom(missing)

        assertThat(report).isEqualTo(ArmorLoaderService.LoadReport(loaded = 0, failed = 0))
    }
}
