package com.astarworks.astera.core.application.service.vanilla

import com.astarworks.astera.core.domain.vanilla.VanillaFeature
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InMemoryVanillaFeatureSuppressorTest {

    @Test
    fun `fresh suppressor reports nothing as suppressed`() {
        val s = InMemoryVanillaFeatureSuppressor()
        assertThat(s.suppressed()).isEmpty()
        VanillaFeature.all.forEach { assertThat(s.isSuppressed(it)).isFalse() }
    }

    @Test
    fun `suppress then isSuppressed returns true`() {
        val s = InMemoryVanillaFeatureSuppressor()
        s.suppress(VanillaFeature.VanillaAdvancement)
        assertThat(s.isSuppressed(VanillaFeature.VanillaAdvancement)).isTrue()
        assertThat(s.isSuppressed(VanillaFeature.VanillaDeathMessage)).isFalse()
    }

    @Test
    fun `suppress is idempotent`() {
        val s = InMemoryVanillaFeatureSuppressor()
        repeat(5) { s.suppress(VanillaFeature.VanillaRecipe) }
        assertThat(s.suppressed()).hasSize(1).containsExactly(VanillaFeature.VanillaRecipe)
    }

    @Test
    fun `restore removes suppression`() {
        val s = InMemoryVanillaFeatureSuppressor()
        s.suppress(VanillaFeature.VanillaXpBar)
        s.restore(VanillaFeature.VanillaXpBar)
        assertThat(s.isSuppressed(VanillaFeature.VanillaXpBar)).isFalse()
        assertThat(s.suppressed()).isEmpty()
    }

    @Test
    fun `restore on non-suppressed feature is a no-op`() {
        val s = InMemoryVanillaFeatureSuppressor()
        s.restore(VanillaFeature.VanillaPortalTraversal) // must not throw
        assertThat(s.suppressed()).isEmpty()
    }

    @Test
    fun `suppressed returns snapshot independent of subsequent mutations`() {
        val s = InMemoryVanillaFeatureSuppressor()
        s.suppress(VanillaFeature.VanillaWeatherCycle)
        val snap = s.suppressed()
        s.suppress(VanillaFeature.VanillaHungerBar)
        assertThat(snap).hasSize(1).containsExactly(VanillaFeature.VanillaWeatherCycle)
        assertThat(s.suppressed()).hasSize(2)
    }

    @Test
    fun `all 8 documented features are addressable`() {
        val s = InMemoryVanillaFeatureSuppressor()
        VanillaFeature.all.forEach { s.suppress(it) }
        assertThat(s.suppressed()).hasSize(8)
    }
}
