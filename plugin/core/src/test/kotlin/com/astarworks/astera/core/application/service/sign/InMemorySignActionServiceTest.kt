package com.astarworks.astera.core.application.service.sign

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.sign.SignAction
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class InMemorySignActionServiceTest {

    private val world = WorldId("world")
    private val pos = Vec3(0.0, 64.0, 0.0)

    @Test
    fun `bind then lookup resolves the action`() {
        val s = InMemorySignActionService()
        val action = SignAction.Display(MessageKey("astera.welcome"))

        s.bindSignAction(world, pos, action)

        assertThat(s.lookup(world, pos)).isEqualTo(action)
        assertThat(s.size).isEqualTo(1)
    }

    @Test
    fun `rebind overwrites`() {
        val s = InMemorySignActionService()
        s.bindSignAction(world, pos, SignAction.Display(MessageKey("a")))
        s.bindSignAction(world, pos, SignAction.Display(MessageKey("b")))

        assertThat(s.lookup(world, pos)).isEqualTo(SignAction.Display(MessageKey("b")))
        assertThat(s.size).isEqualTo(1)
    }

    @Test
    fun `unbind returns true on first call false on second`() {
        val s = InMemorySignActionService()
        s.bindSignAction(world, pos, SignAction.Display(MessageKey("a")))

        assertThat(s.unbindSign(world, pos)).isTrue
        assertThat(s.unbindSign(world, pos)).isFalse
        assertThat(s.lookup(world, pos)).isNull()
    }

    @Test
    fun `lookup is per world and position`() {
        val s = InMemorySignActionService()
        val a = Vec3(0.0, 64.0, 0.0)
        val b = Vec3(1.0, 64.0, 0.0)
        s.bindSignAction(world, a, SignAction.Display(MessageKey("a")))

        assertThat(s.lookup(world, a)).isNotNull
        assertThat(s.lookup(world, b)).isNull()
        assertThat(s.lookup(WorldId("nether"), a)).isNull()
    }
}
