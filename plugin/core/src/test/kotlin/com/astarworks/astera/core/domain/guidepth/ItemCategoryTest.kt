package com.astarworks.astera.core.domain.guidepth

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class ItemCategoryTest {

    @Test
    fun `accepts lower-kebab-case`() {
        assertThat(ItemCategory("pvp-weapon").value).isEqualTo("pvp-weapon")
        assertThat(ItemCategory("a").value).isEqualTo("a")
        assertThat(ItemCategory("survival-tool-2").value).isEqualTo("survival-tool-2")
    }

    @Test
    fun `rejects uppercase`() {
        assertThatIllegalArgumentException().isThrownBy { ItemCategory("PvpWeapon") }
    }

    @Test
    fun `rejects leading digit`() {
        assertThatIllegalArgumentException().isThrownBy { ItemCategory("2-weapon") }
    }

    @Test
    fun `rejects underscore and dot`() {
        assertThatIllegalArgumentException().isThrownBy { ItemCategory("pvp_weapon") }
        assertThatIllegalArgumentException().isThrownBy { ItemCategory("pvp.weapon") }
    }

    @Test
    fun `rejects empty`() {
        assertThatIllegalArgumentException().isThrownBy { ItemCategory("") }
    }
}
