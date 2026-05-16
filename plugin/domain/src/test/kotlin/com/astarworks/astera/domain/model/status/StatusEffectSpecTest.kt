package com.astarworks.astera.domain.model.status

import com.astarworks.astera.domain.model.i18n.MessageKey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StatusEffectSpecTest {

    @Test
    fun `accepts maxStacks of 1`() {
        val spec = StatusEffectSpec(
            id = StatusEffectId("burn"),
            displayNameKey = MessageKey("astera.status.burn.name"),
            kind = StatusKind.BURN,
            magnitude = 1.0,
            stackable = false,
            maxStacks = 1,
        )
        assertThat(spec.maxStacks).isEqualTo(1)
    }

    @Test
    fun `rejects maxStacks below 1`() {
        assertThatThrownBy {
            StatusEffectSpec(
                id = StatusEffectId("burn"),
                displayNameKey = MessageKey("astera.status.burn.name"),
                kind = StatusKind.BURN,
                magnitude = 1.0,
                stackable = true,
                maxStacks = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
