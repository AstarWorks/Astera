package com.astarworks.astera.core.domain.block

import com.astarworks.astera.core.domain.world.BlockMaterial
import com.astarworks.astera.core.domain.world.ToolType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Pins the invariants of [BlockDamageProfile] (Wave 5x, ADR-0090).
 */
internal class BlockDamageProfileTest {

    @Test
    fun `accepts zero baseAmount`() {
        val profile = BlockDamageProfile(baseAmount = 0, effectiveTool = ToolType.NONE)
        assertThat(profile.baseAmount).isEqualTo(0)
    }

    @Test
    fun `rejects negative baseAmount`() {
        assertThatThrownBy {
            BlockDamageProfile(baseAmount = -1, effectiveTool = ToolType.IRON_PICKAXE)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("baseAmount must be >= 0")
    }

    @Test
    fun `materialOverride stays optional`() {
        val none = BlockDamageProfile(baseAmount = 50, effectiveTool = ToolType.IRON_PICKAXE)
        assertThat(none.materialOverride).isNull()

        val withOverride = BlockDamageProfile(
            baseAmount = 50,
            effectiveTool = ToolType.IRON_PICKAXE,
            materialOverride = BlockMaterial.STONE,
        )
        assertThat(withOverride.materialOverride).isEqualTo(BlockMaterial.STONE)
    }
}
