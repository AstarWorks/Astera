package com.astarworks.astera.core.application.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Contract tests for [ServiceRegistry] — the cross-plugin port wiring
 * surface introduced by ADR-0022.
 */
class ServiceRegistryTest {

    private interface Greeter {
        fun greet(): String
    }

    private class Hello : Greeter {
        override fun greet(): String = "hello"
    }

    private class Bonjour : Greeter {
        override fun greet(): String = "bonjour"
    }

    @Test
    fun `register then get returns the registered implementation`() {
        val registry = ServiceRegistry()
        val impl = Hello()

        registry.register<Greeter>(impl)

        val resolved = registry.get<Greeter>()
        assertThat(resolved).isSameAs(impl)
        assertThat(resolved?.greet()).isEqualTo("hello")
    }

    @Test
    fun `higher priority wins when multiple impls are registered for the same type`() {
        val registry = ServiceRegistry()
        val low = Hello()
        val high = Bonjour()

        registry.register<Greeter>(low, priority = 0)
        registry.register<Greeter>(high, priority = 100)

        val resolved = registry.get<Greeter>()
        assertThat(resolved).isSameAs(high)
        assertThat(resolved?.greet()).isEqualTo("bonjour")
    }

    @Test
    fun `unregister drops the entry and get returns null afterwards`() {
        val registry = ServiceRegistry()
        val impl = Hello()

        val registration = registry.register<Greeter>(impl)
        assertThat(registration.isActive).isTrue()
        assertThat(registry.get<Greeter>()).isSameAs(impl)

        registration.unregister()
        assertThat(registration.isActive).isFalse()
        assertThat(registry.get<Greeter>()).isNull()
    }

    @Test
    fun `get for an unregistered type returns null`() {
        val registry = ServiceRegistry()

        assertThat(registry.get<Greeter>()).isNull()
    }

    @Test
    fun `getOrThrow throws for an unregistered type and returns impl otherwise`() {
        val registry = ServiceRegistry()
        val impl = Hello()

        assertThat(
            runCatching { registry.getOrThrow<Greeter>() }.exceptionOrNull(),
        ).isInstanceOf(IllegalStateException::class.java)

        registry.register<Greeter>(impl)
        assertThat(registry.getOrThrow<Greeter>()).isSameAs(impl)
    }

    @Test
    fun `unregistering the higher priority entry falls back to the lower one`() {
        val registry = ServiceRegistry()
        val low = Hello()
        val high = Bonjour()

        registry.register<Greeter>(low, priority = 0)
        val highReg = registry.register<Greeter>(high, priority = 50)
        assertThat(registry.get<Greeter>()).isSameAs(high)

        highReg.unregister()
        assertThat(registry.get<Greeter>()).isSameAs(low)
    }
}
