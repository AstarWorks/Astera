package com.astarworks.astera.core.domain.world

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Pins the Wave 5x two-axis [ToolType] (ADR-0090).
 *
 * Wave 5d's single-axis enum collapsed kind+tier; this suite verifies
 * the kind × tier split is enforced + parsed correctly.
 */
internal class ToolTypeTest {

    @Test
    fun `NONE companion is both-NONE pairing`() {
        assertThat(ToolType.NONE.kind).isEqualTo(ToolKind.NONE)
        assertThat(ToolType.NONE.tier).isEqualTo(ToolTier.NONE)
    }

    @Test
    fun `WOODEN_PICKAXE pairs PICKAXE with WOOD`() {
        assertThat(ToolType.WOODEN_PICKAXE.kind).isEqualTo(ToolKind.PICKAXE)
        assertThat(ToolType.WOODEN_PICKAXE.tier).isEqualTo(ToolTier.WOOD)
    }

    @Test
    fun `NETHERITE_SHOVEL pairs SHOVEL with NETHERITE`() {
        assertThat(ToolType.NETHERITE_SHOVEL.kind).isEqualTo(ToolKind.SHOVEL)
        assertThat(ToolType.NETHERITE_SHOVEL.tier).isEqualTo(ToolTier.NETHERITE)
    }

    @Test
    fun `rejects single-NONE pairing (kind NONE with non-NONE tier)`() {
        assertThatThrownBy { ToolType(ToolKind.NONE, ToolTier.IRON) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("kind and tier must both be NONE")
    }

    @Test
    fun `rejects single-NONE pairing (non-NONE kind with NONE tier)`() {
        assertThatThrownBy { ToolType(ToolKind.PICKAXE, ToolTier.NONE) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("kind and tier must both be NONE")
    }

    @Test
    fun `serialKey of NONE is NONE`() {
        assertThat(ToolType.NONE.serialKey()).isEqualTo("NONE")
    }

    @Test
    fun `serialKey of IRON_PICKAXE is PICKAXE_IRON`() {
        assertThat(ToolType.IRON_PICKAXE.serialKey()).isEqualTo("PICKAXE/IRON")
    }

    @Test
    fun `fromSerialKey parses NONE`() {
        assertThat(ToolType.fromSerialKey("NONE")).isEqualTo(ToolType.NONE)
        assertThat(ToolType.fromSerialKey("none")).isEqualTo(ToolType.NONE)
        assertThat(ToolType.fromSerialKey("NONE/NONE")).isEqualTo(ToolType.NONE)
    }

    @Test
    fun `fromSerialKey parses KIND_TIER case-insensitively`() {
        assertThat(ToolType.fromSerialKey("PICKAXE/IRON")).isEqualTo(ToolType.IRON_PICKAXE)
        assertThat(ToolType.fromSerialKey("pickaxe/iron")).isEqualTo(ToolType.IRON_PICKAXE)
        assertThat(ToolType.fromSerialKey("  axe/diamond  ")).isEqualTo(ToolType.DIAMOND_AXE)
    }

    @Test
    fun `fromSerialKey rejects malformed input`() {
        assertThat(ToolType.fromSerialKey("PICKAXE")).isNull()
        assertThat(ToolType.fromSerialKey("PICKAXE/")).isNull()
        assertThat(ToolType.fromSerialKey("HAMMER/IRON")).isNull()
        assertThat(ToolType.fromSerialKey("PICKAXE/PLATINUM")).isNull()
        // Single-NONE pairings would fail init
        assertThat(ToolType.fromSerialKey("PICKAXE/NONE")).isNull()
        assertThat(ToolType.fromSerialKey("NONE/IRON")).isNull()
    }

    @Test
    fun `data class equality is by axes`() {
        val a = ToolType(ToolKind.AXE, ToolTier.STONE)
        val b = ToolType.STONE_AXE
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }
}
