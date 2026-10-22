package com.astarworks.astera.items.adapter.paper

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ItemModelCatalogTest {

    @Test
    fun `an item with generated art gets its astera item_model key`() {
        val catalog = ItemModelCatalog(setOf("fire-staff"))
        assertThat(catalog.modelKey("fire-staff")).isEqualTo("astera:fire-staff")
        assertThat(catalog.modelKey("gui-tile-shop")).isNull()
    }

    @Test
    fun `the catalog reads the generated model files of the content dir`(@TempDir tmp: Path) {
        val dir = tmp.resolve("models/generated/items")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("pistol.model.json"), "{}")
        Files.writeString(dir.resolve("README.md"), "ignored")
        val catalog = ItemModelCatalog.fromContentDir(tmp)
        assertThat(catalog.modelKey("pistol")).isEqualTo("astera:pistol")
        assertThat(catalog.modelKey("README")).isNull()
        assertThat(ItemModelCatalog.fromContentDir(tmp.resolve("missing")).modelKey("pistol")).isNull()
    }

    @Test
    fun `a rescan picks up models pushed after boot`(@TempDir tmp: Path) {
        val catalog = ItemModelCatalog.fromContentDir(tmp)
        assertThat(catalog.modelKey("pistol")).isNull()

        val dir = tmp.resolve("models/generated/items")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("pistol.model.json"), "{}")
        assertThat(catalog.modelKey("pistol")).isNull()

        catalog.rescan()
        assertThat(catalog.modelKey("pistol")).isEqualTo("astera:pistol")
    }

    @Test
    fun `every committed weapon of the real content has an item_model key`() {
        val contentDir = Path.of("../../content") // test working dir is plugin/items
        val catalog = ItemModelCatalog.fromContentDir(contentDir)
        for (id in listOf("pistol", "rifle", "shotgun", "knight-sword", "greatsword", "dagger",
            "frost-staff", "fire-staff", "thunder-staff", "verdant-staff")) {
            assertThat(catalog.modelKey(id)).`as`(id).isEqualTo("astera:$id")
        }
    }
}
