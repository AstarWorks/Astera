package com.astarworks.astera.core.domain.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ClearScopeTest {

    @Test
    fun `Slot accepts 0 and 40`() {
        assertThat(ClearScope.Slot(0).slotIndex).isEqualTo(0)
        assertThat(ClearScope.Slot(40).slotIndex).isEqualTo(40)
    }

    @Test
    fun `Slot rejects -1 and 41`() {
        assertThatThrownBy { ClearScope.Slot(-1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ClearScope.Slot(41) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `singleton scopes are equal`() {
        assertThat(ClearScope.All).isSameAs(ClearScope.All)
        assertThat(ClearScope.Hotbar).isSameAs(ClearScope.Hotbar)
        assertThat(ClearScope.Armor).isSameAs(ClearScope.Armor)
    }
}
