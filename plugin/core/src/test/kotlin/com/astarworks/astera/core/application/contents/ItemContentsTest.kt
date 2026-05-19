package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.item.DropPolicy
import com.astarworks.astera.core.domain.item.DurabilityPolicy
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemAttributes
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.item.ItemTemplate
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Migrated from `MutableItemRegistryTest` in Wave _infra-2b (ADR-0065);
 * simplified in Wave _items-1 (ADR-0073) — the generic `T : Any` + mapper
 * lambda were dropped, so this test surface now works directly on
 * [ItemSpec].
 */
class ItemContentsTest {

    private val validVanilla = """
        id: example-sword
        material: IRON_SWORD
        name:
          en: "Example Sword"
          ja: "サンプルソード"
        rarity: rare
        level_requirement: 5
        attributes:
          attack_damage: 7.0
        equip_slot: mainhand
        pdc_tags:
          astera:family: blade
    """.trimIndent()

    private val validExternal = """
        id: my-blade
        external_key: oraxen:my_blade
    """.trimIndent()

    @Test
    fun `loads vanilla item config from yaml`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("example-sword.yaml"), validVanilla)

        val contents = ItemContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        val spec = contents.find(ItemId("example-sword"))
        assertThat(spec).isNotNull
        requireNotNull(spec)
        val template = spec.template as ItemTemplate.Vanilla
        assertThat(template.material).isEqualTo("IRON_SWORD")
        assertThat(spec.rarity).isEqualTo(Rarity.RARE)
        assertThat(spec.levelRequirement).isEqualTo(5)
        assertThat(spec.attributes.attackDamage).isEqualTo(7.0)
        assertThat(spec.equipSlot).isEqualTo(EquipSlot.MAINHAND)
        assertThat(spec.displayName.byLocale[LocaleCode.EN]).isEqualTo("Example Sword")
        assertThat(spec.displayName.byLocale[LocaleCode.JA]).isEqualTo("サンプルソード")
        assertThat(spec.pdcTags).containsEntry("astera:family", "blade")
    }

    @Test
    fun `loads external item config from yaml`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("my-blade.yaml"), validExternal)

        val contents = ItemContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        val spec = contents.find(ItemId("my-blade"))
        val external = spec?.template as? ItemTemplate.External
        assertThat(external?.externalKey).isEqualTo("oraxen:my_blade")
    }

    @Test
    fun `rejects entries with neither material nor external_key`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve("bad.yaml"),
            """
                id: bad-item
            """.trimIndent(),
        )

        val contents = ItemContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.all()).isEmpty()
    }

    @Test
    fun `rejects entries with both material and external_key`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve("bad.yaml"),
            """
                id: bad-item
                material: IRON_SWORD
                external_key: oraxen:my_blade
            """.trimIndent(),
        )

        val contents = ItemContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(1)
    }

    @Test
    fun `rejects malformed item id (uppercase) via ContentLoader fail-soft`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve("bad.yaml"),
            """
                id: BadId
                material: IRON_SWORD
            """.trimIndent(),
        )

        val contents = ItemContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.all()).isEmpty()
    }

    @Test
    fun `missing directory returns zero counts`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist")
        val contents = ItemContents()

        val report = contents.bootstrap(missing)

        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.all()).isEmpty()
    }

    // ----- Reload semantics ------------------------------------------------

    @Test
    fun `reload swaps to new map when all files parse`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("example-sword.yaml"), validVanilla)

        val contents = ItemContents()
        contents.bootstrap(dir)
        assertThat(contents.all()).hasSize(1)

        Files.delete(dir.resolve("example-sword.yaml"))
        Files.writeString(
            dir.resolve("replacement.yaml"),
            validVanilla.replace("example-sword", "replacement"),
        )

        val report = contents.reload(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.find(ItemId("example-sword"))).isNull()
        assertThat(contents.find(ItemId("replacement"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `reload is all-or-nothing - one bad file keeps old state intact`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("example-sword.yaml"), validVanilla)

        val contents = ItemContents()
        contents.bootstrap(dir)
        assertThat(contents.find(ItemId("example-sword"))).isNotNull

        Files.writeString(dir.resolve("broken.yaml"), "id: : : not valid : yaml :::\n  - oops")

        val report = contents.reload(dir)

        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(ItemId("example-sword"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `register adds an entry without going through YAML`() {
        val contents = ItemContents()
        val id = ItemId("manual-entry")
        val spec = ItemSpec(
            id = id,
            template = ItemTemplate.Vanilla(material = "STONE"),
            displayName = LocalizedText.EMPTY,
            lore = LocalizedLore.EMPTY,
            rarity = Rarity.COMMON,
            levelRequirement = 1,
            attributes = ItemAttributes.EMPTY,
            equipSlot = EquipSlot.MAINHAND,
            durability = DurabilityPolicy.DEFAULT,
            dropPolicy = DropPolicy.DEFAULT,
            enchantable = true,
            glint = null,
            skillBindings = emptyList(),
            pdcTags = emptyMap(),
        )

        contents.register(id, spec)

        assertThat(contents.find(id)).isNotNull
        assertThat(contents.all()).hasSize(1)

        contents.clear()
        assertThat(contents.find(id)).isNull()
        assertThat(contents.all()).isEmpty()
    }
}
