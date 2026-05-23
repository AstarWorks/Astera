package com.astarworks.astera.core.domain.mob.loot

import com.astarworks.astera.core.domain.item.ItemId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LootDropTest {

    private val sword = ItemId("astera-sword")

    @Test
    fun `guaranteed fixed-count drop`() {
        val drop = LootDrop(itemId = sword, chance = 1.0)
        assertThat(drop.isGuaranteed).isTrue
        assertThat(drop.isFixedCount).isTrue
        assertThat(drop.minCount).isEqualTo(1)
    }

    @Test
    fun `chance must be in unit interval`() {
        assertThatThrownBy { LootDrop(sword, chance = 1.5) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { LootDrop(sword, chance = -0.1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `chance NaN rejected`() {
        assertThatThrownBy { LootDrop(sword, chance = Double.NaN) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `range count drop`() {
        val drop = LootDrop(sword, chance = 0.5, minCount = 1, maxCount = 3)
        assertThat(drop.isFixedCount).isFalse
        assertThat(drop.minCount).isEqualTo(1)
        assertThat(drop.maxCount).isEqualTo(3)
    }

    @Test
    fun `minCount under 1 rejected`() {
        assertThatThrownBy { LootDrop(sword, chance = 0.5, minCount = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `maxCount under minCount rejected`() {
        assertThatThrownBy {
            LootDrop(sword, chance = 0.5, minCount = 5, maxCount = 4)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be >=")
    }
}
