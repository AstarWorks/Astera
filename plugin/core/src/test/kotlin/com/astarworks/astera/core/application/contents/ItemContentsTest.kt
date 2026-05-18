package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.ItemYamlConfig
import com.astarworks.astera.core.domain.item.ItemId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Migrated from `MutableItemRegistryTest` in Wave _infra-2b (ADR-0065).
 *
 * The legacy `MutableItemRegistry` is replaced by [ItemContents], a subclass
 * of [YamlDynamicContents] that gains atomic-swap reload semantics for free.
 * The test surface adds reload-semantics checks (Wave _infra-2 goal); the
 * rest of the cases mirror the legacy file 1:1 to keep coverage.
 */
class ItemContentsTest {

    private data class FakeTemplate(val id: ItemId, val material: String?, val externalKey: String?)

    private val defaultMapper: (ItemYamlConfig) -> FakeTemplate = { cfg ->
        FakeTemplate(ItemId(cfg.id), cfg.material, cfg.externalKey)
    }

    private val validVanilla = """
        id: example-sword
        material: IRON_SWORD
        display_name_key: astera.item.example-sword.name
        lore_keys:
          - astera.item.example-sword.lore
        pdc_tags:
          astera:rarity: common
    """.trimIndent()

    private val validExternal = """
        id: my-blade
        external_key: oraxen:my_blade
    """.trimIndent()

    @Test
    fun `loads vanilla item config from yaml`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("example-sword.yaml"), validVanilla)

        val contents = ItemContents(mapper = defaultMapper)
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        val tpl = contents.find(ItemId("example-sword"))
        assertThat(tpl).isNotNull
        requireNotNull(tpl)
        assertThat(tpl.material).isEqualTo("IRON_SWORD")
        assertThat(tpl.externalKey).isNull()
    }

    @Test
    fun `loads external item config from yaml`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("my-blade.yaml"), validExternal)

        val contents = ItemContents(mapper = defaultMapper)
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        val tpl = contents.find(ItemId("my-blade"))
        assertThat(tpl?.externalKey).isEqualTo("oraxen:my_blade")
        assertThat(tpl?.material).isNull()
    }

    @Test
    fun `rejects entries with neither material nor external_key`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve("bad.yaml"),
            """
                id: bad-item
                display_name_key: astera.item.bad.name
            """.trimIndent(),
        )

        val contents = ItemContents(mapper = defaultMapper)
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

        val contents = ItemContents(mapper = defaultMapper)
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

        val contents = ItemContents(mapper = defaultMapper)
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.all()).isEmpty()
    }

    @Test
    fun `missing directory returns zero counts`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist")
        val contents = ItemContents(mapper = defaultMapper)

        val report = contents.bootstrap(missing)

        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.all()).isEmpty()
    }

    // ----- Reload semantics (Wave _infra-2b new) ---------------------------

    @Test
    fun `reload swaps to new map when all files parse`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("example-sword.yaml"), validVanilla)

        val contents = ItemContents(mapper = defaultMapper)
        contents.bootstrap(dir)
        assertThat(contents.all()).hasSize(1)

        // Replace the single item with a different one.
        Files.delete(dir.resolve("example-sword.yaml"))
        Files.writeString(dir.resolve("replacement.yaml"), validVanilla.replace("example-sword", "replacement"))

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

        val contents = ItemContents(mapper = defaultMapper)
        contents.bootstrap(dir)
        assertThat(contents.find(ItemId("example-sword"))).isNotNull

        Files.writeString(dir.resolve("broken.yaml"), "id: : : not valid : yaml :::\n  - oops")

        val report = contents.reload(dir)

        // failed > 0 means reload aborts; old state preserved.
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(ItemId("example-sword"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `register adds an entry without going through YAML`() {
        val contents = ItemContents(mapper = defaultMapper)
        val id = ItemId("manual-entry")

        contents.register(id, FakeTemplate(id, "STONE", null))

        assertThat(contents.find(id)).isNotNull
        assertThat(contents.all()).hasSize(1)

        contents.clear()
        assertThat(contents.find(id)).isNull()
        assertThat(contents.all()).isEmpty()
    }
}
