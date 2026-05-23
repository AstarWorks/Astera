package com.astarworks.astera.items.domain.glow

import com.astarworks.astera.core.domain.item.Rarity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class GlowProfileTest {

    @Test
    fun `defaults cover every rarity tier`() {
        for (tier in Rarity.entries) {
            assertThat(GlowProfile.DEFAULTS).containsKey(tier)
        }
    }

    @Test
    fun `common default is silent`() {
        val common = GlowProfile.DEFAULTS.getValue(Rarity.COMMON)
        assertThat(common.active).isFalse()
        assertThat(common.particleCount).isZero()
        assertThat(common.visibleFromBlocks).isZero()
    }

    @Test
    fun `legendary default broadcasts across lobby`() {
        val legendary = GlowProfile.DEFAULTS.getValue(Rarity.LEGENDARY)
        assertThat(legendary.active).isTrue()
        assertThat(legendary.glintOverride).isTrue()
        assertThat(legendary.visibleFromBlocks).isGreaterThanOrEqualTo(16)
    }

    @Test
    fun `intensity scales monotonically with rarity`() {
        // particleTickPeriod decreases (more frequent) and particleCount
        // increases as the tier increases (Common → Legendary).
        val tiers = Rarity.entries.toList()
        val active = tiers.drop(1).map { GlowProfile.DEFAULTS.getValue(it) }
        // Skip COMMON (0/0); among Uncommon..Legendary tickPeriod monotonically decreases.
        val periods = active.map { it.particleTickPeriod }
        assertThat(periods).isSortedAccordingTo(Comparator.reverseOrder())
        val counts = active.map { it.particleCount }
        assertThat(counts).isSorted
    }

    @Test
    fun `negative particleTickPeriod is rejected`() {
        assertThatThrownBy {
            GlowProfile(
                rarity = Rarity.COMMON,
                particleTickPeriod = -1,
                particleCount = 0,
                glintOverride = false,
                visibleFromBlocks = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `negative particleCount is rejected`() {
        assertThatThrownBy {
            GlowProfile(
                rarity = Rarity.COMMON,
                particleTickPeriod = 1,
                particleCount = -1,
                glintOverride = false,
                visibleFromBlocks = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `negative visibleFromBlocks is rejected`() {
        assertThatThrownBy {
            GlowProfile(
                rarity = Rarity.COMMON,
                particleTickPeriod = 1,
                particleCount = 1,
                glintOverride = false,
                visibleFromBlocks = -1,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `active is false when either tickPeriod or particleCount is zero`() {
        val zeroPeriod = GlowProfile(Rarity.COMMON, 0, 1, false, 0)
        val zeroCount = GlowProfile(Rarity.COMMON, 1, 0, false, 0)
        assertThat(zeroPeriod.active).isFalse()
        assertThat(zeroCount.active).isFalse()
    }
}
