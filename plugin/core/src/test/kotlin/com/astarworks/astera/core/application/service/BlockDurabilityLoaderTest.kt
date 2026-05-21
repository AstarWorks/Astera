package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.world.BlockMaterial
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Pins the fail-soft load contract of [BlockDurabilityLoader] (Wave 5d →
 * Wave 5x, ADR-0090): missing, unreadable, malformed, and validation-failing
 * YAML all return the default matrix so the server still boots.
 *
 * Wave 5x switched the YAML schema to the two-axis `KIND/TIER` tool key;
 * test inputs use the new format.
 */
internal class BlockDurabilityLoaderTest {

    private val loader = BlockDurabilityLoader()

    @Test
    fun `missing file returns default matrix`(@TempDir dir: Path) {
        val missing = dir.resolve("block-durability.yaml")
        val rule = loader.load(missing)

        // Default matrix has known cells we can spot-check.
        assertThat(rule.damageFor(ToolType.IRON_PICKAXE, BlockMaterial.STONE)).isEqualTo(100)
        assertThat(rule.damageFor(ToolType.NONE, BlockMaterial.STONE)).isEqualTo(0)
        assertThat(rule.materialOf(BlockType("minecraft:stone"))).isEqualTo(BlockMaterial.STONE)
    }

    @Test
    fun `valid YAML file returns parsed rule`(@TempDir dir: Path) {
        val file = dir.resolve("block-durability.yaml")
        file.writeText(
            """
            materials:
              "minecraft:obsidian": stone
              "minecraft:custom_wood": wood
            damage_matrix:
              "PICKAXE/WOOD":
                stone: 10
                soil: 90
              "PICKAXE/DIAMOND":
                stone: 100
            """.trimIndent(),
        )

        val rule = loader.load(file)
        assertThat(rule.damageFor(ToolType.WOODEN_PICKAXE, BlockMaterial.STONE)).isEqualTo(10)
        assertThat(rule.damageFor(ToolType.WOODEN_PICKAXE, BlockMaterial.SOIL)).isEqualTo(90)
        assertThat(rule.damageFor(ToolType.DIAMOND_PICKAXE, BlockMaterial.STONE)).isEqualTo(100)
        assertThat(rule.materialOf(BlockType("minecraft:obsidian"))).isEqualTo(BlockMaterial.STONE)
        assertThat(rule.materialOf(BlockType("minecraft:custom_wood"))).isEqualTo(BlockMaterial.WOOD)
    }

    @Test
    fun `malformed YAML falls back to default matrix`(@TempDir dir: Path) {
        val file = dir.resolve("block-durability.yaml")
        file.writeText("damage_matrix: [this is not a map}")

        val rule = loader.load(file)
        // Falls back to the default matrix
        assertThat(rule.damageFor(ToolType.IRON_PICKAXE, BlockMaterial.STONE)).isEqualTo(100)
    }

    @Test
    fun `validation failure falls back to default matrix`(@TempDir dir: Path) {
        val file = dir.resolve("block-durability.yaml")
        // Unknown tool key "HAMMER/PLATINUM" -> validation should fail -> default matrix
        file.writeText(
            """
            damage_matrix:
              "HAMMER/PLATINUM":
                stone: 25
            """.trimIndent(),
        )

        val rule = loader.load(file)
        // Confirm we got the default matrix back (not a partially-loaded rule)
        assertThat(rule.damageFor(ToolType.IRON_PICKAXE, BlockMaterial.STONE)).isEqualTo(100)
        assertThat(rule.damageFor(ToolType.IRON_SHOVEL, BlockMaterial.SOIL)).isEqualTo(100)
    }

    @Test
    fun `empty YAML file returns an empty-but-valid rule`(@TempDir dir: Path) {
        val file = dir.resolve("block-durability.yaml")
        file.writeText("{}")

        val rule = loader.load(file)
        // No matrix entries means every cell hits DEFAULT_DAMAGE fallback
        assertThat(rule.damageFor(ToolType.WOODEN_PICKAXE, BlockMaterial.STONE))
            .isEqualTo(com.astarworks.astera.core.domain.world.BlockDurabilityRule.DEFAULT_DAMAGE)
    }
}
