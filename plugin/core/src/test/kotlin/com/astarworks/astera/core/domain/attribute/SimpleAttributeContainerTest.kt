package com.astarworks.astera.core.domain.attribute

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Wave 5c tests for [SimpleAttributeContainer]'s modifier resolution.
 *
 * The read-API surface (`get` / `set` / `keys` / extension helpers) is already
 * exercised by [AttributeContainerTest] via the in-test `MapContainer` shim;
 * this file focuses on the modifier-specific behaviour.
 *
 * Resolution order being tested (ADR-0058 §1):
 *   1. base = `(get(key) as NumberValue?)?.value ?: 0.0`
 *   2. SET — last by registration order replaces base
 *   3. ADD — sum all ADDs onto the SET/base result
 *   4. MULTIPLY — compound in registration order
 */
internal class SimpleAttributeContainerTest {

    private val key: AttributeKey = AttributeKey("test.value")

    @Test
    fun `empty container resolves effectiveNumber to 0_0`() {
        val container = SimpleAttributeContainer()

        assertThat(container.effectiveNumber(key)).isEqualTo(0.0)
        assertThat(container.modifiers()).isEmpty()
        assertThat(container.keys()).isEmpty()
    }

    @Test
    fun `base value only - no modifiers`() {
        val container = SimpleAttributeContainer()
        container.set(key, AttributeValue.NumberValue(42.0))

        assertThat(container.effectiveNumber(key)).isEqualTo(42.0)
    }

    @Test
    fun `non-number base reads as zero baseline`() {
        val container = SimpleAttributeContainer()
        // A FlagValue stored under the key should not contribute a base number.
        container.set(key, AttributeValue.FlagValue(true))

        assertThat(container.effectiveNumber(key)).isEqualTo(0.0)
    }

    @Test
    fun `ADD modifiers sum onto base`() {
        val container = SimpleAttributeContainer()
        container.set(key, AttributeValue.NumberValue(10.0))
        container.addModifier(AttributeModifier(key, AttributeModifier.Operation.ADD, 5.0, "src-a"))
        container.addModifier(AttributeModifier(key, AttributeModifier.Operation.ADD, 7.0, "src-b"))

        assertThat(container.effectiveNumber(key)).isEqualTo(22.0)
    }

    @Test
    fun `MULTIPLY modifiers compound in registration order`() {
        val container = SimpleAttributeContainer()
        container.set(key, AttributeValue.NumberValue(100.0))
        container.addModifier(AttributeModifier(key, AttributeModifier.Operation.MULTIPLY, 0.5, "src-a"))
        container.addModifier(AttributeModifier(key, AttributeModifier.Operation.MULTIPLY, 0.5, "src-b"))

        // 100 * 0.5 * 0.5 = 25
        assertThat(container.effectiveNumber(key)).isEqualTo(25.0)
    }

    @Test
    fun `SET replaces base before ADD applies`() {
        val container = SimpleAttributeContainer()
        container.set(key, AttributeValue.NumberValue(10.0))
        container.addModifier(AttributeModifier(key, AttributeModifier.Operation.ADD, 7.0, "src-add"))
        container.addModifier(AttributeModifier(key, AttributeModifier.Operation.SET, 100.0, "src-set"))

        // SET replaces base (10 -> 100), then ADD sums (100 + 7 = 107)
        assertThat(container.effectiveNumber(key)).isEqualTo(107.0)
    }

    @Test
    fun `last SET wins by registration order`() {
        val container = SimpleAttributeContainer()
        container.set(key, AttributeValue.NumberValue(10.0))
        container.addModifier(AttributeModifier(key, AttributeModifier.Operation.SET, 50.0, "src-first"))
        container.addModifier(AttributeModifier(key, AttributeModifier.Operation.SET, 200.0, "src-second"))

        assertThat(container.effectiveNumber(key)).isEqualTo(200.0)
    }

    @Test
    fun `full pipeline - SET then ADD then MULTIPLY`() {
        val container = SimpleAttributeContainer()
        container.set(key, AttributeValue.NumberValue(1.0)) // irrelevant once SET applies
        container.addModifier(AttributeModifier(key, AttributeModifier.Operation.SET, 100.0, "src-set"))
        container.addModifier(AttributeModifier(key, AttributeModifier.Operation.ADD, 20.0, "src-add"))
        container.addModifier(AttributeModifier(key, AttributeModifier.Operation.MULTIPLY, 0.5, "src-mult-a"))
        container.addModifier(AttributeModifier(key, AttributeModifier.Operation.MULTIPLY, 0.5, "src-mult-b"))

        // (100 + 20) * 0.5 * 0.5 = 30
        assertThat(container.effectiveNumber(key)).isEqualTo(30.0)
    }

    @Test
    fun `modifiers targeting a different key are ignored`() {
        val container = SimpleAttributeContainer()
        val other = AttributeKey("test.other")
        container.set(key, AttributeValue.NumberValue(10.0))
        container.addModifier(AttributeModifier(other, AttributeModifier.Operation.ADD, 999.0, "src-other"))

        assertThat(container.effectiveNumber(key)).isEqualTo(10.0)
    }

    @Test
    fun `removeModifiersBySource removes only matching source`() {
        val container = SimpleAttributeContainer()
        container.set(key, AttributeValue.NumberValue(10.0))
        container.addModifier(AttributeModifier(key, AttributeModifier.Operation.ADD, 5.0, "src-x"))
        container.addModifier(AttributeModifier(key, AttributeModifier.Operation.ADD, 3.0, "src-y"))
        container.addModifier(AttributeModifier(key, AttributeModifier.Operation.ADD, 11.0, "src-x"))

        container.removeModifiersBySource("src-x")

        assertThat(container.modifiers()).hasSize(1)
        assertThat(container.modifiers().single().sourceId).isEqualTo("src-y")
        // 10 (base) + 3 (src-y) = 13
        assertThat(container.effectiveNumber(key)).isEqualTo(13.0)
    }

    @Test
    fun `removeModifiersBySource is a no-op for an unknown source`() {
        val container = SimpleAttributeContainer()
        container.addModifier(AttributeModifier(key, AttributeModifier.Operation.ADD, 5.0, "src-x"))

        container.removeModifiersBySource("not-present")

        assertThat(container.modifiers()).hasSize(1)
    }

    @Test
    fun `modifiers returns registration order`() {
        val container = SimpleAttributeContainer()
        val m1 = AttributeModifier(key, AttributeModifier.Operation.ADD, 1.0, "src-a")
        val m2 = AttributeModifier(key, AttributeModifier.Operation.MULTIPLY, 2.0, "src-b")
        val m3 = AttributeModifier(key, AttributeModifier.Operation.SET, 3.0, "src-c")

        container.addModifier(m1)
        container.addModifier(m2)
        container.addModifier(m3)

        assertThat(container.modifiers()).containsExactly(m1, m2, m3)
    }

    @Test
    fun `modifiers returns a defensive copy`() {
        val container = SimpleAttributeContainer()
        container.addModifier(AttributeModifier(key, AttributeModifier.Operation.ADD, 5.0, "src-a"))

        val snapshot = container.modifiers()
        container.addModifier(AttributeModifier(key, AttributeModifier.Operation.ADD, 7.0, "src-b"))

        assertThat(snapshot).hasSize(1)
        assertThat(container.modifiers()).hasSize(2)
    }
}
