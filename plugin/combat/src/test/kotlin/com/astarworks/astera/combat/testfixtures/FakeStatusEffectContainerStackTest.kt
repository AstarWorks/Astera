package com.astarworks.astera.combat.testfixtures

import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.combat.domain.status.StatusEffectSpec
import com.astarworks.astera.combat.domain.status.StatusKind
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class FakeStatusEffectContainerStackTest {
    private val player = PlayerId(UUID.randomUUID())
    private val spec = StatusEffectSpec(
        id = StatusEffectId("rupture"),
        displayNameKey = MessageKey("astera.status.rupture.name"),
        kind = StatusKind.STACK_DEBUFF,
        magnitude = 0.0,
        stackable = true,
        maxStacks = 10,
    )

    private fun inst(stacks: Int) = StatusEffectInstance(spec, player, 100, stacks, 0L)

    @Test
    fun `apply adds stacks capped at max and removeStacks consumes them`() {
        val c = FakeStatusEffectContainer()
        c.apply(inst(4))
        c.apply(inst(9))
        assertThat(c.stacks(player, spec.id)).isEqualTo(10)

        c.removeStacks(player, spec.id, 3)
        assertThat(c.stacks(player, spec.id)).isEqualTo(7)
        c.removeStacks(player, spec.id, 7)
        assertThat(c.has(player, spec.id)).isFalse()
        assertThat(c.stacks(player, spec.id)).isZero()
    }
}
