package com.astarworks.astera.items.application.service.glow

import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.items.domain.glow.GlowProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InMemoryGlowProfileRegistryTest {

    @Test
    fun `defaults seeded at construction`() {
        val r = InMemoryGlowProfileRegistry()
        for (tier in Rarity.entries) {
            assertThat(r.resolve(tier)).isEqualTo(GlowProfile.DEFAULTS.getValue(tier))
        }
    }

    @Test
    fun `register replaces only the given tier`() {
        val r = InMemoryGlowProfileRegistry()
        val override = GlowProfile(
            rarity = Rarity.EPIC,
            particleTickPeriod = 15,
            particleCount = 5,
            glintOverride = true,
            visibleFromBlocks = 12,
        )
        r.register(override)

        assertThat(r.resolve(Rarity.EPIC)).isEqualTo(override)
        assertThat(r.resolve(Rarity.LEGENDARY))
            .isEqualTo(GlowProfile.DEFAULTS.getValue(Rarity.LEGENDARY))
    }

    @Test
    fun `registerAll bulk-replaces`() {
        val r = InMemoryGlowProfileRegistry()
        val a = GlowProfile(Rarity.COMMON, 10, 1, false, 1)
        val b = GlowProfile(Rarity.UNCOMMON, 5, 2, false, 2)
        r.registerAll(listOf(a, b))
        assertThat(r.resolve(Rarity.COMMON)).isEqualTo(a)
        assertThat(r.resolve(Rarity.UNCOMMON)).isEqualTo(b)
        assertThat(r.resolve(Rarity.LEGENDARY))
            .isEqualTo(GlowProfile.DEFAULTS.getValue(Rarity.LEGENDARY))
    }

    @Test
    fun `snapshot returns every tier in enum order`() {
        val r = InMemoryGlowProfileRegistry()
        val snap = r.snapshot()
        assertThat(snap.map { it.rarity }).containsExactly(*Rarity.entries.toTypedArray())
    }
}
