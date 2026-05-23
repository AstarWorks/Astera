package com.astarworks.astera.items.domain.refinement

import com.astarworks.astera.core.domain.attribute.AttributeModifier
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RefinementMathTest {

    @Test
    fun `every layer maps to a MULTIPLY modifier on REFINED_POWER_KEY`() {
        val layers: List<Refinement> = listOf(
            Refinement.Reforge("sharp", 3),
            Refinement.Enchant("fire_aspect", 2),
            Refinement.Gemstone(0, "ruby", 4),
            Refinement.Star(7),
            Refinement.Recomb(15),
        )
        for (layer in layers) {
            val (key, op, _) = RefinementMath.modifierFor(layer)
            assertThat(key).isEqualTo(RefinementMath.REFINED_POWER_KEY)
            assertThat(op).isEqualTo(AttributeModifier.Operation.MULTIPLY)
        }
    }

    @Test
    fun `Reforge contributes 1 plus 0_10 times tier`() {
        val mod = RefinementMath.modifierFor(Refinement.Reforge("sharp", 3))
        assertThat(mod.value).isEqualTo(1.30)
    }

    @Test
    fun `Star contributes 1 plus 0_08 times stars`() {
        val mod = RefinementMath.modifierFor(Refinement.Star(5))
        assertThat(mod.value).isEqualTo(1.40)
    }

    @Test
    fun `rerollCost follows escalating 900-1000-2000-2500-3000 ladder`() {
        assertThat(RefinementMath.rerollCost(0)).isEqualTo(900L)
        assertThat(RefinementMath.rerollCost(1)).isEqualTo(1000L)
        assertThat(RefinementMath.rerollCost(2)).isEqualTo(2000L)
        assertThat(RefinementMath.rerollCost(3)).isEqualTo(2500L)
        assertThat(RefinementMath.rerollCost(4)).isEqualTo(3000L)
        // Plateau after 5+
        assertThat(RefinementMath.rerollCost(10)).isEqualTo(3000L)
        assertThat(RefinementMath.rerollCost(100)).isEqualTo(3000L)
    }

    @Test
    fun `rerollCost rejects negative index`() {
        assertThatThrownBy { RefinementMath.rerollCost(-1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
