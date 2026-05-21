package com.astarworks.astera.core.domain.block

import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.world.BlockMaterial
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Pins the invariants of [CustomBlockSpec] + [BlockDrop] (Wave 5x, ADR-0090).
 */
internal class CustomBlockSpecTest {

    private fun spec(
        baseDurability: Int = 100,
        baseBukkitMaterial: String = "DIRT",
    ) = CustomBlockSpec(
        id = BlockType("astera:test_block"),
        displayName = LocalizedText(mapOf(LocaleCode.EN to "Test")),
        baseBukkitMaterial = baseBukkitMaterial,
        material = BlockMaterial.SOIL,
        baseDurability = baseDurability,
    )

    @Test
    fun `accepts a normal spec`() {
        val s = spec()
        assertThat(s.baseDurability).isEqualTo(100)
        assertThat(s.toolEffectiveness).isNull()
        assertThat(s.drops).isEmpty()
    }

    @Test
    fun `rejects zero baseDurability`() {
        assertThatThrownBy { spec(baseDurability = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("baseDurability must be >= 1")
    }

    @Test
    fun `rejects blank baseBukkitMaterial`() {
        assertThatThrownBy { spec(baseBukkitMaterial = "  ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("baseBukkitMaterial must not be blank")
    }

    @Test
    fun `BlockDrop accepts single-value range`() {
        val drop = BlockDrop(itemId = ItemId("dirt-clump"), countRange = 1..1)
        assertThat(drop.countRange).isEqualTo(1..1)
    }

    @Test
    fun `BlockDrop rejects empty range`() {
        assertThatThrownBy { BlockDrop(itemId = ItemId("x"), countRange = 5..1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("countRange must be non-empty")
    }

    @Test
    fun `BlockDrop rejects dropChance outside 0_1`() {
        assertThatThrownBy { BlockDrop(itemId = ItemId("x"), dropChance = -0.1f) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { BlockDrop(itemId = ItemId("x"), dropChance = 1.5f) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `BlockDrop accepts requiresTool gate`() {
        val drop = BlockDrop(
            itemId = ItemId("rare"),
            requiresTool = ToolType.IRON_PICKAXE,
        )
        assertThat(drop.requiresTool).isEqualTo(ToolType.IRON_PICKAXE)
    }
}
