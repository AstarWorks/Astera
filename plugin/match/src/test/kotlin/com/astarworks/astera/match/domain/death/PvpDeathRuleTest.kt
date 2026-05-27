package com.astarworks.astera.match.domain.death

import com.astarworks.astera.core.domain.item.ItemId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PvpDeathRuleTest {

    @Test
    fun `sealed variants are distinct singletons except DropOnly`() {
        assertThat(PvpDeathRule.KeepInventory).isSameAs(PvpDeathRule.KeepInventory)
        assertThat(PvpDeathRule.DropAll).isSameAs(PvpDeathRule.DropAll)
        assertThat(PvpDeathRule.DropEquipped).isSameAs(PvpDeathRule.DropEquipped)
        assertThat(PvpDeathRule.DropOnly(0.5f)).isNotSameAs(PvpDeathRule.DropOnly(0.5f))
        // …but data-class equality holds.
        assertThat(PvpDeathRule.DropOnly(0.5f)).isEqualTo(PvpDeathRule.DropOnly(0.5f))
    }

    @Test
    fun `DropOnly accepts 0_0 1_0 boundary values`() {
        assertThat(PvpDeathRule.DropOnly(0.0f).percent).isEqualTo(0.0f)
        assertThat(PvpDeathRule.DropOnly(1.0f).percent).isEqualTo(1.0f)
    }

    @Test
    fun `DropOnly rejects out-of-range percent`() {
        assertThatThrownBy { PvpDeathRule.DropOnly(-0.1f) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("percent")
        assertThatThrownBy { PvpDeathRule.DropOnly(1.1f) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("percent")
    }

    @Test
    fun `DropOnly defaults includes to empty set`() {
        assertThat(PvpDeathRule.DropOnly(0.5f).includes).isEmpty()
    }

    @Test
    fun `DropOnly preserves includes set`() {
        val ids = setOf(ItemId("amethyst-sword"), ItemId("aqua-axe"))
        assertThat(PvpDeathRule.DropOnly(0.7f, ids).includes).isEqualTo(ids)
    }
}
