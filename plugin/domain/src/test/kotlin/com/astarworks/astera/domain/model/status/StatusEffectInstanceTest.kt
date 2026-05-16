package com.astarworks.astera.domain.model.status

import com.astarworks.astera.domain.model.i18n.MessageKey
import com.astarworks.astera.domain.model.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class StatusEffectInstanceTest {

    private val spec = StatusEffectSpec(
        id = StatusEffectId("burn"),
        displayNameKey = MessageKey("astera.status.burn.name"),
        kind = StatusKind.BURN,
        magnitude = 1.0,
        stackable = true,
        maxStacks = 3,
    )

    private val target = PlayerId(UUID.randomUUID())

    @Test
    fun `accepts zero remaining ticks and single stack`() {
        val inst = StatusEffectInstance(
            spec = spec,
            targetPlayerId = target,
            remainingTicks = 0,
            currentStacks = 1,
            appliedAtTick = 100L,
        )
        assertThat(inst.remainingTicks).isEqualTo(0)
        assertThat(inst.currentStacks).isEqualTo(1)
    }

    @Test
    fun `rejects negative remaining ticks`() {
        assertThatThrownBy {
            StatusEffectInstance(
                spec = spec,
                targetPlayerId = target,
                remainingTicks = -1,
                currentStacks = 1,
                appliedAtTick = 0L,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects stacks below 1`() {
        assertThatThrownBy {
            StatusEffectInstance(
                spec = spec,
                targetPlayerId = target,
                remainingTicks = 5,
                currentStacks = 0,
                appliedAtTick = 0L,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
