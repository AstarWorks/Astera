package com.astarworks.astera.core.domain.attribute

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Wave 5b ADR-0058 §1–§3 minimum read-API tests.
 *
 * Verifies that the value-type validation rejects malformed keys and that
 * the read extensions discriminate variant types correctly. The container
 * interface itself is tested via the [ShimPlayerAttributeContainer] in the
 * combat plugin.
 */
internal class AttributeContainerTest {

    @Test
    fun `AttributeKey accepts namespaced lower-snake keys`() {
        assertThat(AttributeKey("stat.max_hp").namespaced).isEqualTo("stat.max_hp")
        assertThat(AttributeKey("resource.mana_current").namespaced).isEqualTo("resource.mana_current")
        assertThat(AttributeKey("modifier.cooldown_reduction").namespaced).isEqualTo("modifier.cooldown_reduction")
        assertThat(AttributeKey("a.b.c").namespaced).isEqualTo("a.b.c")
    }

    @Test
    fun `AttributeKey rejects malformed input`() {
        assertThatThrownBy { AttributeKey("MaxHP") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AttributeKey("nodot") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AttributeKey("Stat.MaxHp") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AttributeKey("") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AttributeKey(".trailing") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AttributeKey("leading.") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Attributes registry exposes well-known keys`() {
        assertThat(Attributes.MAX_HP.namespaced).isEqualTo("stat.max_hp")
        assertThat(Attributes.MANA_CURRENT.namespaced).isEqualTo("resource.mana_current")
        assertThat(Attributes.RESIST_FIRE.namespaced).isEqualTo("resist.fire")
        assertThat(Attributes.COOLDOWN_REDUCTION.namespaced).isEqualTo("modifier.cooldown_reduction")
    }

    @Test
    fun `number extension reads NumberValue and ignores other variants`() {
        val container = MapContainer()
        container.set(Attributes.MAX_HP, AttributeValue.NumberValue(25.0))
        container.set(Attributes.RESIST_FIRE, AttributeValue.FlagValue(true))

        assertThat(container.number(Attributes.MAX_HP)).isEqualTo(25.0)
        assertThat(container.number(Attributes.RESIST_FIRE)).isNull()
        assertThat(container.number(Attributes.MANA_CURRENT)).isNull()
    }

    @Test
    fun `flag and ref and durationTicks extensions discriminate variants`() {
        val container = MapContainer()
        val flagKey = AttributeKey("test.flag")
        val refKey = AttributeKey("test.ref")
        val durationKey = AttributeKey("test.duration")

        container.set(flagKey, AttributeValue.FlagValue(true))
        container.set(refKey, AttributeValue.RefValue("weapon:axe"))
        container.set(durationKey, AttributeValue.DurationValue(40L))

        assertThat(container.flag(flagKey)).isTrue()
        assertThat(container.flag(refKey)).isNull()
        assertThat(container.ref(refKey)).isEqualTo("weapon:axe")
        assertThat(container.ref(flagKey)).isNull()
        assertThat(container.durationTicks(durationKey)).isEqualTo(40L)
        assertThat(container.durationTicks(flagKey)).isNull()
    }

    @Test
    fun `setNumber and setFlag convenience writers store typed values`() {
        val container = MapContainer()
        container.setNumber(Attributes.MAX_HP, 30.0)
        container.setFlag(AttributeKey("test.invulnerable"), true)

        assertThat(container.number(Attributes.MAX_HP)).isEqualTo(30.0)
        assertThat(container.flag(AttributeKey("test.invulnerable"))).isTrue()
    }

    @Test
    fun `remove deletes a stored value`() {
        val container = MapContainer()
        container.setNumber(Attributes.MAX_HP, 20.0)
        assertThat(container.keys()).contains(Attributes.MAX_HP)

        container.remove(Attributes.MAX_HP)
        assertThat(container.get(Attributes.MAX_HP)).isNull()
        assertThat(container.keys()).doesNotContain(Attributes.MAX_HP)
    }

    /** Minimal in-memory container used only for this test file. */
    private class MapContainer : IAttributeContainer {
        private val backing = mutableMapOf<AttributeKey, AttributeValue>()
        override fun get(key: AttributeKey): AttributeValue? = backing[key]
        override fun set(key: AttributeKey, value: AttributeValue) { backing[key] = value }
        override fun remove(key: AttributeKey) { backing.remove(key) }
        override fun keys(): Set<AttributeKey> = backing.keys.toSet()
    }
}
