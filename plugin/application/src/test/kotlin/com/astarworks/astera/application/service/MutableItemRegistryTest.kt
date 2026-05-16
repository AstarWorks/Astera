package com.astarworks.astera.application.service

import com.astarworks.astera.application.config.ItemYamlConfig
import com.astarworks.astera.domain.model.item.ItemId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Stage 1 D smoke tests for [MutableItemRegistry]. Verifies the YAML →
 * IItemRegistry pipeline without pulling in `IMcItemTemplate` (application
 * layer must stay vendor-neutral).
 */
class MutableItemRegistryTest {

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

        val registry = MutableItemRegistry(mapper = defaultMapper)
        val report = registry.loadFrom(dir)

        assertThat(report).isEqualTo(MutableItemRegistry.LoadReport(loaded = 1, failed = 0))
        val tpl = registry.resolveOrNull(ItemId("example-sword"))
        assertThat(tpl).isNotNull
        requireNotNull(tpl)
        assertThat(tpl.material).isEqualTo("IRON_SWORD")
        assertThat(tpl.externalKey).isNull()
    }

    @Test
    fun `loads external item config from yaml`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("my-blade.yaml"), validExternal)

        val registry = MutableItemRegistry(mapper = defaultMapper)
        val report = registry.loadFrom(dir)

        assertThat(report.loaded).isEqualTo(1)
        val tpl = registry.resolveOrNull(ItemId("my-blade"))
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

        val registry = MutableItemRegistry(mapper = defaultMapper)
        val report = registry.loadFrom(dir)

        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(1)
        assertThat(registry.list()).isEmpty()
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

        val registry = MutableItemRegistry(mapper = defaultMapper)
        val report = registry.loadFrom(dir)

        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(1)
    }

    @Test
    fun `register and clear behave as expected`() {
        val registry = MutableItemRegistry(mapper = defaultMapper)
        val id = ItemId("manual-entry")
        registry.register(id, FakeTemplate(id, "STONE", null))
        assertThat(registry.resolveOrNull(id)).isNotNull
        assertThat(registry.list()).hasSize(1)

        registry.clear()
        assertThat(registry.resolveOrNull(id)).isNull()
        assertThat(registry.list()).isEmpty()
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

        val registry = MutableItemRegistry(mapper = defaultMapper)
        val report = registry.loadFrom(dir)

        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(1)
        assertThat(registry.list()).isEmpty()
    }
}
