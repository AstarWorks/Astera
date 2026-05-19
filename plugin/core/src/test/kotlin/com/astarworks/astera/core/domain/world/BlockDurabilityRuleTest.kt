package com.astarworks.astera.core.domain.world

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the default damage matrix + material binding behaviour for
 * [BlockDurabilityRule]. Cells are checked against the Wave 5d spec table
 * (see RTM `BlockState.md`).
 */
internal class BlockDurabilityRuleTest {

    private val rule = BlockDurabilityRule.defaultMatrix()

    // --- Matrix cells ---

    @Test
    fun `wood tool one-hits soil`() {
        assertThat(rule.damageFor(ToolType.WOOD, BlockMaterial.SOIL)).isEqualTo(100)
    }

    @Test
    fun `wood tool multi-hits stone`() {
        assertThat(rule.damageFor(ToolType.WOOD, BlockMaterial.STONE)).isEqualTo(25)
    }

    @Test
    fun `bare fist cannot damage stone`() {
        assertThat(rule.damageFor(ToolType.NONE, BlockMaterial.STONE)).isEqualTo(0)
    }

    @Test
    fun `diamond tool one-hits OTHER`() {
        assertThat(rule.damageFor(ToolType.DIAMOND, BlockMaterial.OTHER)).isEqualTo(100)
    }

    @Test
    fun `stone tool half-damages soil`() {
        assertThat(rule.damageFor(ToolType.STONE, BlockMaterial.SOIL)).isEqualTo(50)
    }

    @Test
    fun `iron tool one-hits every material`() {
        BlockMaterial.entries.forEach { mat ->
            assertThat(rule.damageFor(ToolType.IRON, mat))
                .`as`("iron vs %s", mat)
                .isEqualTo(100)
        }
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

    // --- damageFor(tool, blockType) convenience ---

    @Test
    fun `damageFor by BlockType resolves through materialOf`() {
        // unknown block -> OTHER row -> WOOD tool against OTHER = 25
        assertThat(rule.damageFor(ToolType.WOOD, BlockType("unknown:foo"))).isEqualTo(25)
        // known stone block -> STONE row -> WOOD tool = 25
        assertThat(rule.damageFor(ToolType.WOOD, BlockType("minecraft:stone"))).isEqualTo(25)
        // known soil -> SOIL row -> WOOD tool = 100
        assertThat(rule.damageFor(ToolType.WOOD, BlockType("minecraft:dirt"))).isEqualTo(100)
    }

    // --- DEFAULT_DAMAGE fallback for missing cells ---

    @Test
    fun `missing cell returns DEFAULT_DAMAGE`() {
        val sparse = BlockDurabilityRule(
            matrix = mapOf((ToolType.IRON to BlockMaterial.STONE) to 77),
        )
        // Cell present
        assertThat(sparse.damageFor(ToolType.IRON, BlockMaterial.STONE)).isEqualTo(77)
        // Cell absent -> default
        assertThat(sparse.damageFor(ToolType.WOOD, BlockMaterial.SOIL))
            .isEqualTo(BlockDurabilityRule.DEFAULT_DAMAGE)
    }
}
