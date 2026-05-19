package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.attribute.number
import com.astarworks.astera.core.domain.attribute.setNumber
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5e (ADR-0058 §5) tests for the canonical in-memory player attribute
 * container store plus the modifier-resolution contract callers rely on.
 */
internal class InMemoryPlayerAttributeContainerTest {

    private val p1 = PlayerId(UUID.randomUUID())
    private val p2 = PlayerId(UUID.randomUUID())

    @Test
    fun `containerFor returns the same container for the same player`() {
        val store = InMemoryPlayerAttributeContainer()

        val a = store.containerFor(p1)
        val b = store.containerFor(p1)

        assertThat(a).isSameAs(b)
    }

    @Test
    fun `containerFor returns distinct containers for distinct players`() {
        val store = InMemoryPlayerAttributeContainer()

        val a = store.containerFor(p1)
        val b = store.containerFor(p2)

        assertThat(a).isNotSameAs(b)
    }

    @Test
    fun `loaded snapshot reflects every player touched via containerFor`() {
        val store = InMemoryPlayerAttributeContainer()
        store.containerFor(p1).setNumber(Attributes.STAT_KILLS, 3.0)
        store.containerFor(p2)

        val loaded = store.loaded()

        assertThat(loaded.keys).containsExactlyInAnyOrder(p1, p2)
        assertThat(loaded[p1]!!.number(Attributes.STAT_KILLS)).isEqualTo(3.0)
    }

    @Test
    fun `flush is a no-op for the in-memory store`() {
        val store = InMemoryPlayerAttributeContainer()
        store.containerFor(p1).setNumber(Attributes.WALLET_STAR, 100.0)

        store.flush(p1) // no exception, no state change

        assertThat(store.containerFor(p1).number(Attributes.WALLET_STAR)).isEqualTo(100.0)
    }

    @Test
    fun `effectiveNumber composes SET then ADD then MULTIPLY in spec order`() {
        val c = SimpleAttributeContainer()
        c.setNumber(Attributes.STAT_KILLS, 10.0) // base 10
        c.addModifier(AttributeModifier(Attributes.STAT_KILLS, AttributeModifier.Operation.SET, 50.0, "setter"))
        c.addModifier(AttributeModifier(Attributes.STAT_KILLS, AttributeModifier.Operation.ADD, 5.0, "adder"))
        c.addModifier(AttributeModifier(Attributes.STAT_KILLS, AttributeModifier.Operation.MULTIPLY, 2.0, "doubler"))

        // SET replaces base → 50; ADD 5 → 55; MULTIPLY 2 → 110
        assertThat(c.effectiveNumber(Attributes.STAT_KILLS)).isEqualTo(110.0)
    }

    @Test
    fun `effectiveNumber falls back to base when no modifiers apply`() {
        val c = SimpleAttributeContainer()
        c.setNumber(Attributes.STAT_WINS, 4.0)

        assertThat(c.effectiveNumber(Attributes.STAT_WINS)).isEqualTo(4.0)
    }

    @Test
    fun `removeModifiersBySource removes every modifier sharing that source`() {
        val c = SimpleAttributeContainer()
        c.setNumber(Attributes.RESIST_FIRE, 0.0)
        c.addModifier(
            AttributeModifier(Attributes.RESIST_FIRE, AttributeModifier.Operation.ADD, 0.3, "armor:fire-chest"),
        )
        c.addModifier(
            AttributeModifier(Attributes.RESIST_FIRE, AttributeModifier.Operation.ADD, 0.2, "armor:fire-chest"),
        )
        c.addModifier(
            AttributeModifier(Attributes.RESIST_FIRE, AttributeModifier.Operation.ADD, 0.5, "armor:fire-helm"),
        )

        assertThat(c.effectiveNumber(Attributes.RESIST_FIRE)).isEqualTo(1.0)

        c.removeModifiersBySource("armor:fire-chest")

        // Only the helm contribution remains.
        assertThat(c.effectiveNumber(Attributes.RESIST_FIRE)).isEqualTo(0.5)
        // And no fire-chest modifier survived.
        assertThat(c.modifiers().none { it.sourceId == "armor:fire-chest" }).isTrue()
    }

    @Test
    fun `last SET wins regardless of registration order`() {
        val c = SimpleAttributeContainer()
        c.setNumber(Attributes.MANA_MAX, 100.0)
        c.addModifier(AttributeModifier(Attributes.MANA_MAX, AttributeModifier.Operation.SET, 30.0, "a"))
        c.addModifier(AttributeModifier(Attributes.MANA_MAX, AttributeModifier.Operation.SET, 50.0, "b"))

        // Last SET (50) wins, replacing base 100.
        assertThat(c.effectiveNumber(Attributes.MANA_MAX)).isEqualTo(50.0)
    }

    @Test
    fun `set propagates to subsequent effectiveNumber reads`() {
        val c = SimpleAttributeContainer()
        c.set(Attributes.WALLET_GOLD, AttributeValue.NumberValue(0.0))
        assertThat(c.effectiveNumber(Attributes.WALLET_GOLD)).isEqualTo(0.0)

        c.set(Attributes.WALLET_GOLD, AttributeValue.NumberValue(42.0))
        assertThat(c.effectiveNumber(Attributes.WALLET_GOLD)).isEqualTo(42.0)
    }
}
