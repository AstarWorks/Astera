package com.astarworks.astera.combat.application.contents

import com.astarworks.astera.combat.domain.armor.ArmorId
import com.astarworks.astera.combat.domain.armor.ArmorReduction
import com.astarworks.astera.combat.domain.armor.ArmorSet
import com.astarworks.astera.combat.domain.armor.ArmorSetKey
import com.astarworks.astera.combat.domain.armor.ArmorSlot
import com.astarworks.astera.combat.domain.armor.ArmorSpec
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Migrated from `ArmorLoaderServiceTest` in Wave _infra-2b (ADR-0065).
 *
 * The legacy `MutableArmorRegistry` + `ArmorLoaderService` pair is replaced by
 * a single [ArmorContents] that hand-implements
 * [com.astarworks.astera.core.application.contents.ReloadableContents] across
 * two parallel maps (pieces + sets). Adds reload-semantics checks (Wave
 * _infra-2b goal) covering both maps; the rest mirrors the legacy file.
 */
class ArmorContentsTest {

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
    fun `bootstrap loads a piece`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-helmet.yaml"), pieceYaml)

        val contents = ArmorContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        val spec = contents.find(ArmorId("test-helmet"))
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

        val contents = ArmorContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        val spec = contents.find(ArmorId("fire-chest"))!!
        assertThat(spec.reduction.default).isEqualTo(0.20)
        assertThat(spec.reduction.forAttribute(DamageAttribute.FIRE)).isEqualTo(0.60)
        assertThat(spec.reduction.forAttribute(DamageAttribute.PHYSICAL)).isEqualTo(0.10)
        assertThat(spec.reduction.forAttribute(DamageAttribute.ELECTRIC)).isEqualTo(0.20) // fallback
    }

    @Test
    fun `bootstrap loads a set definition`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-set.yaml"), setYaml)

        val contents = ArmorContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        val set = contents.findSet(ArmorSetKey("test-set"))
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

        val contents = ArmorContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(2)
        assertThat(report.failed).isEqualTo(0)
        val piece = contents.find(ArmorId("test-chestplate"))
        assertThat(piece).isNotNull
        assertThat(piece!!.setKey).isEqualTo(ArmorSetKey("test-set"))
        assertThat(contents.findSet(ArmorSetKey("test-set"))).isNotNull
    }

    @Test
    fun `bootstrap is best-effort - malformed yaml is skipped and other files still load`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("good.yaml"), pieceYaml)
        Files.writeString(dir.resolve("broken.yaml"), "::: not valid : yaml :::\n  - oops")

        val contents = ArmorContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(ArmorId("test-helmet"))).isNotNull
    }

    @Test
    fun `missing directory returns zero counts and registries stay empty`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist")
        val contents = ArmorContents()

        val report = contents.bootstrap(missing)

        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.all()).isEmpty()
        assertThat(contents.allSets()).isEmpty()
    }

    // ----- Reload semantics (Wave _infra-2b new) ---------------------------

    @Test
    fun `reload swaps both pieces and sets when all files parse`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("piece.yaml"), pieceWithSetYaml)
        Files.writeString(dir.resolve("set.yaml"), setYaml)

        val contents = ArmorContents()
        contents.bootstrap(dir)
        assertThat(contents.all()).hasSize(1)
        assertThat(contents.allSets()).hasSize(1)

        // Replace both with renamed equivalents
        Files.delete(dir.resolve("piece.yaml"))
        Files.delete(dir.resolve("set.yaml"))
        Files.writeString(
            dir.resolve("replacement-piece.yaml"),
            pieceWithSetYaml
                .replace("test-chestplate", "replacement-chestplate")
                .replace("set_key: test-set", "set_key: replacement-set"),
        )
        Files.writeString(
            dir.resolve("replacement-set.yaml"),
            setYaml.replace("test-set", "replacement-set"),
        )

        val report = contents.reload(dir)

        assertThat(report.loaded).isEqualTo(2)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.find(ArmorId("test-chestplate"))).isNull()
        assertThat(contents.find(ArmorId("replacement-chestplate"))).isNotNull
        assertThat(contents.findSet(ArmorSetKey("test-set"))).isNull()
        assertThat(contents.findSet(ArmorSetKey("replacement-set"))).isNotNull
    }

    @Test
    fun `reload is all-or-nothing - one bad file keeps both old maps intact`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("piece.yaml"), pieceWithSetYaml)
        Files.writeString(dir.resolve("set.yaml"), setYaml)

        val contents = ArmorContents()
        contents.bootstrap(dir)
        assertThat(contents.find(ArmorId("test-chestplate"))).isNotNull
        assertThat(contents.findSet(ArmorSetKey("test-set"))).isNotNull

        Files.writeString(dir.resolve("broken.yaml"), "::: not valid : yaml :::\n  - oops")

        val report = contents.reload(dir)

        // failed > 0 means reload aborts; BOTH old maps preserved atomically.
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(ArmorId("test-chestplate"))).isNotNull
        assertThat(contents.findSet(ArmorSetKey("test-set"))).isNotNull
        assertThat(contents.all()).hasSize(1)
        assertThat(contents.allSets()).hasSize(1)
    }

    @Test
    fun `reload aborts cross-map when a set yaml is malformed`(@TempDir dir: Path) {
        // Start with a piece + a set
        Files.writeString(dir.resolve("piece.yaml"), pieceYaml)
        Files.writeString(dir.resolve("set.yaml"), setYaml)

        val contents = ArmorContents()
        contents.bootstrap(dir)
        assertThat(contents.find(ArmorId("test-helmet"))).isNotNull
        assertThat(contents.findSet(ArmorSetKey("test-set"))).isNotNull

        // Corrupt only the set file (keep pieces well-formed). A failure on the
        // sets side must still abort the pieces swap.
        Files.writeString(dir.resolve("set.yaml"), "type: set\nkey: bad\npieces: not-a-list")

        val report = contents.reload(dir)

        assertThat(report.failed).isGreaterThan(0)
        // Both pre-existing entries remain — atomic across pieces+sets.
        assertThat(contents.find(ArmorId("test-helmet"))).isNotNull
        assertThat(contents.findSet(ArmorSetKey("test-set"))).isNotNull
    }

    @Test
    fun `register adds a piece without going through YAML`() {
        val contents = ArmorContents()
        val spec = ArmorSpec(
            id = ArmorId("programmatic"),
            displayNameKey = MessageKey("test.helmet"),
            slot = ArmorSlot.HELMET,
            reduction = ArmorReduction.flat(0.30),
            setKey = null,
        )

        contents.register(spec)

        assertThat(contents.find(ArmorId("programmatic"))).isEqualTo(spec)
    }

    @Test
    fun `registerSet adds a set without going through YAML`() {
        val contents = ArmorContents()
        val set = ArmorSet(
            key = ArmorSetKey("programmatic-set"),
            displayNameKey = MessageKey("test.set"),
            pieces = setOf(ArmorId("a"), ArmorId("b")),
            twoPieceBonus = 0.10,
            fourPieceBonus = 0.25,
        )

        contents.registerSet(set)

        assertThat(contents.findSet(ArmorSetKey("programmatic-set"))).isEqualTo(set)
    }
}
