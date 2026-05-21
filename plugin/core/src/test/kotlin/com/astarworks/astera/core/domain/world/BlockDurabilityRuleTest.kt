package com.astarworks.astera.core.domain.world

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the default damage matrix + material binding behaviour for
 * [BlockDurabilityRule] (Wave 5d → Wave 5x, ADR-0090).
 *
 * Wave 5x split `ToolType` into a two-axis `ToolKind × ToolTier`; this
 * suite now exercises the three-way (PICKAXE/AXE/SHOVEL) against the
 * three material columns (WOOD/STONE/SOIL).
 */
internal class BlockDurabilityRuleTest {

    private val rule = BlockDurabilityRule.defaultMatrix()

    // --- Matrix cells: pickaxe excels at stone ---

    @Test
    fun `pickaxe iron one-hits stone`() {
        assertThat(rule.damageFor(ToolType.IRON_PICKAXE, BlockMaterial.STONE)).isEqualTo(100)
    }

    @Test
    fun `pickaxe wood under-damages wood`() {
        assertThat(rule.damageFor(ToolType.WOODEN_PICKAXE, BlockMaterial.WOOD)).isEqualTo(5)
    }

    @Test
    fun `pickaxe diamond cannot damage soil`() {
        assertThat(rule.damageFor(ToolType.DIAMOND_PICKAXE, BlockMaterial.SOIL)).isEqualTo(0)
    }

    // --- Axe excels at wood ---

    @Test
    fun `axe iron one-hits wood`() {
        assertThat(rule.damageFor(ToolType.IRON_AXE, BlockMaterial.WOOD)).isEqualTo(100)
    }

    @Test
    fun `axe iron under-damages stone`() {
        assertThat(rule.damageFor(ToolType.IRON_AXE, BlockMaterial.STONE)).isEqualTo(25)
    }

    @Test
    fun `axe diamond cannot damage soil`() {
        assertThat(rule.damageFor(ToolType.DIAMOND_AXE, BlockMaterial.SOIL)).isEqualTo(0)
    }

    // --- Shovel excels at soil ---

    @Test
    fun `shovel iron one-hits soil`() {
        assertThat(rule.damageFor(ToolType.IRON_SHOVEL, BlockMaterial.SOIL)).isEqualTo(100)
    }

    @Test
    fun `shovel diamond cannot damage stone`() {
        assertThat(rule.damageFor(ToolType.DIAMOND_SHOVEL, BlockMaterial.STONE)).isEqualTo(0)
    }

    @Test
    fun `shovel diamond cannot damage wood`() {
        assertThat(rule.damageFor(ToolType.DIAMOND_SHOVEL, BlockMaterial.WOOD)).isEqualTo(0)
    }

    // --- Bare-hand / NONE ---

    @Test
    fun `bare fist cannot damage stone`() {
        assertThat(rule.damageFor(ToolType.NONE, BlockMaterial.STONE)).isEqualTo(0)
    }

    @Test
    fun `bare fist chips soil`() {
        assertThat(rule.damageFor(ToolType.NONE, BlockMaterial.SOIL)).isEqualTo(10)
    }

    // --- Netherite is the highest tier ---

    @Test
    fun `netherite pickaxe quad-damages stone vs diamond`() {
        val diamond = rule.damageFor(ToolType.DIAMOND_PICKAXE, BlockMaterial.STONE)
        val netherite = rule.damageFor(ToolType.NETHERITE_PICKAXE, BlockMaterial.STONE)
        assertThat(netherite).isEqualTo(diamond * 2)
    }

    // --- materialOf bindings ---

    @Test
    fun `minecraft stone maps to STONE`() {
        assertThat(rule.materialOf(BlockType("minecraft:stone")))
            .isEqualTo(BlockMaterial.STONE)
    }

    @Test
    fun `minecraft oak_log maps to WOOD`() {
        assertThat(rule.materialOf(BlockType("minecraft:oak_log")))
            .isEqualTo(BlockMaterial.WOOD)
    }

    @Test
    fun `minecraft dirt maps to SOIL`() {
        assertThat(rule.materialOf(BlockType("minecraft:dirt")))
            .isEqualTo(BlockMaterial.SOIL)
    }

    @Test
    fun `unknown block falls back to OTHER`() {
        assertThat(rule.materialOf(BlockType("unknown:foo")))
            .isEqualTo(BlockMaterial.OTHER)
    }

    @Test
    fun `astera barrier_block maps to STONE`() {
        assertThat(rule.materialOf(BlockType("astera:barrier_block")))
            .isEqualTo(BlockMaterial.STONE)
    }

    @Test
    fun `astera hardened_soil maps to SOIL`() {
        assertThat(rule.materialOf(BlockType("astera:hardened_soil")))
            .isEqualTo(BlockMaterial.SOIL)
    }

    // --- damageFor(tool, blockType) convenience ---

    @Test
    fun `damageFor by BlockType resolves through materialOf`() {
        // unknown block -> OTHER row -> AXE WOOD tier = 25 (strong curve)
        assertThat(rule.damageFor(ToolType.WOODEN_AXE, BlockType("unknown:foo"))).isEqualTo(25)
        // known stone block -> STONE row -> AXE WOOD tier = 5 (weak curve)
        assertThat(rule.damageFor(ToolType.WOODEN_AXE, BlockType("minecraft:stone"))).isEqualTo(5)
        // known soil -> SOIL row -> SHOVEL IRON tier = 100 (strong)
        assertThat(rule.damageFor(ToolType.IRON_SHOVEL, BlockType("minecraft:dirt"))).isEqualTo(100)
    }

    // --- DEFAULT_DAMAGE fallback for missing cells ---

    @Test
    fun `missing cell returns DEFAULT_DAMAGE`() {
        val sparse = BlockDurabilityRule(
            matrix = mapOf(
                Triple(ToolKind.PICKAXE, ToolTier.IRON, BlockMaterial.STONE) to 77,
            ),
        )
        // Cell present
        assertThat(sparse.damageFor(ToolType.IRON_PICKAXE, BlockMaterial.STONE)).isEqualTo(77)
        // Cell absent -> default
        assertThat(sparse.damageFor(ToolType.WOODEN_AXE, BlockMaterial.SOIL))
            .isEqualTo(BlockDurabilityRule.DEFAULT_DAMAGE)
    }
}
