package com.astarworks.astera.combat.domain.skill.expression

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CelSkillScriptTest {

    @Test
    fun `accepts non-blank source with empty bindings by default`() {
        val script = CelSkillScript(source = "player.attr['stat.max_hp'] * 0.5")
        assertThat(script.source).isEqualTo("player.attr['stat.max_hp'] * 0.5")
        assertThat(script.referencedBindings).isEmpty()
    }

    @Test
    fun `accepts source with declared binding refs`() {
        val bindings = listOf(
            BindingRef(root = "player.attr", key = "stat.max_hp"),
            BindingRef(root = "target.attr", key = "stat.kills"),
        )
        val script = CelSkillScript(
            source = "player.attr['stat.max_hp'] + target.attr['stat.kills']",
            referencedBindings = bindings,
        )
        assertThat(script.referencedBindings).containsExactlyElementsOf(bindings)
    }

    @Test
    fun `rejects blank source`() {
        assertThatThrownBy { CelSkillScript(source = "") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CelSkillScript(source = "   ") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CelSkillScript(source = "\n\t") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `BindingRef construction roundtrips root and key`() {
        val ref = BindingRef(root = "player.attr", key = "stat.max_hp")
        assertThat(ref.root).isEqualTo("player.attr")
        assertThat(ref.key).isEqualTo("stat.max_hp")
    }

    @Test
    fun `BindingRef data equality holds on identical components`() {
        val a = BindingRef("projectile", "speed")
        val b = BindingRef("projectile", "speed")
        val c = BindingRef("projectile", "damage")
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
    }
}
