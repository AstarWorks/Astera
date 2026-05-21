package com.astarworks.astera.core.application.contents.block

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.world.BlockType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Pins the YAML bootstrap → registry round-trip for [CustomBlockContents]
 * (Wave 5x, ADR-0090). Mirrors the `ItemContents` substrate test pattern.
 */
internal class CustomBlockContentsTest {

    @Test
    fun `bootstrap loads a well-formed YAML and exposes find + resolveByItem`(@TempDir dir: Path) {
        dir.resolve("hardened-soil.yaml").writeText(
            """
            id: "astera:hardened_soil"
            display_name:
              en: "Hardened Soil"
            base_bukkit_material: "DIRT"
            material: "SOIL"
            base_durability: 5000
            tool_effectiveness:
              "PICKAXE/IRON": 50
            drops:
              - item_id: "hardened-soil-block"
                count_range: "1..1"
                requires_tool: "PICKAXE/IRON"
            placed_from: "hardened-soil-block"
            """.trimIndent(),
        )

        val contents = CustomBlockContents()
        val report = contents.bootstrap(dir)
        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)

        val spec = contents.resolve(BlockType("astera:hardened_soil"))
        assertThat(spec).isNotNull
        assertThat(spec!!.baseDurability).isEqualTo(5000)

        val byItem = contents.resolveByItem(ItemId("hardened-soil-block"))
        assertThat(byItem).isNotNull
        assertThat(byItem!!.id).isEqualTo(BlockType("astera:hardened_soil"))
    }

    @Test
    fun `bootstrap counts malformed YAML as a failed load`(@TempDir dir: Path) {
        dir.resolve("bad.yaml").writeText(
            """
            id: "astera:bad_block"
            display_name: { en: "Bad" }
            base_bukkit_material: "DIRT"
            material: "GLASS"   # invalid enum
            base_durability: 100
            """.trimIndent(),
        )

        val contents = CustomBlockContents()
        val report = contents.bootstrap(dir)
        assertThat(report.failed).isEqualTo(1)
        assertThat(report.loaded).isEqualTo(0)
        assertThat(contents.find(BlockType("astera:bad_block"))).isNull()
    }

    @Test
    fun `categoryName is blocks`() {
        assertThat(CustomBlockContents().categoryName).isEqualTo("blocks")
    }
}
