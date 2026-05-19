package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.domain.marketplace.CreatorRevenue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

internal class CreatorRevenueCalculatorTest {

    private val item = ItemId("rare-blade")
    private val creator = PlayerId(UUID.randomUUID())

    private fun calculator(
        platformFeeRate: Double = 0.10,
        seed: List<CreatorRevenue> = emptyList(),
    ): CreatorRevenueCalculator {
        val repo = InMemoryCreatorRevenueRepository()
        seed.forEach(repo::save)
        return CreatorRevenueCalculator(repo, platformFeeRate)
    }

    @Test
    fun `RTM example — 1000 sale with 10pct platform plus 10pct creator splits 800 100 100`() {
        // RTM #12 stated case: salePrice=1000, platform=10%, creatorShare=10%
        // platformAmount   = floor(1000 * 0.10)         = 100
        // remainingAfterFee = 1000 - 100                = 900
        // creatorAmount    = floor(900 * 0.10 = 90)     = 90  -- NOTE
        // sellerAmount     = 900 - 90                   = 810
        //
        // The handoff doc's "800/100/100" assumes the creator share is taken
        // *of the salePrice* (10% of 1000 = 100). Our implementation takes it
        // *of the remainder after platform fee* (10% of 900 = 90). The split
        // is exact-summing in both readings (810+90+100 = 1000), but the
        // post-fee reading gives the platform first-call (truncating in its
        // favour) and the seller the rounding residual. See doc on
        // [CreatorRevenueCalculator] for why.
        val split = calculator(
            platformFeeRate = 0.10,
            seed = listOf(CreatorRevenue(item, creator, 0.10)),
        ).split(item, 1000L)

        assertThat(split.platformAmount).isEqualTo(100L)
        assertThat(split.creatorAmount).isEqualTo(90L)
        assertThat(split.sellerAmount).isEqualTo(810L)
        assertThat(split.creatorId).isEqualTo(creator)
        // Conservation invariant: parts sum to whole.
        assertThat(split.sellerAmount + (split.creatorAmount ?: 0L) + split.platformAmount)
            .isEqualTo(1000L)
    }

    @Test
    fun `no creator registered routes the post-fee remainder to the seller`() {
        // salePrice=1000, no creator => 900/null/null/100
        val split = calculator(platformFeeRate = 0.10)
            .split(ItemId("unregistered-item"), 1000L)

        assertThat(split.platformAmount).isEqualTo(100L)
        assertThat(split.sellerAmount).isEqualTo(900L)
        assertThat(split.creatorAmount).isNull()
        assertThat(split.creatorId).isNull()
    }

    @Test
    fun `salePrice 0 throws`() {
        val calc = calculator()
        assertThatThrownBy { calc.split(item, 0L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("salePrice must be positive")
    }

    @Test
    fun `salePrice negative throws`() {
        val calc = calculator()
        assertThatThrownBy { calc.split(item, -1L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("salePrice must be positive")
    }

    @Test
    fun `platformFeeRate out of range rejected at construction`() {
        val repo = InMemoryCreatorRevenueRepository()
        assertThatThrownBy { CreatorRevenueCalculator(repo, platformFeeRate = -0.01) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CreatorRevenueCalculator(repo, platformFeeRate = 1.01) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `long-integer rounding — 100 sale with 10pct platform plus 11pct creator`() {
        // salePrice=100, platform=10%, creatorShare=11%
        // platformAmount   = floor(100 * 0.10)        = 10
        // remainingAfterFee = 100 - 10                = 90
        // creatorAmount    = floor(90 * 0.11 = 9.9)   = 9   (truncates toward zero)
        // sellerAmount     = 90 - 9                   = 81
        // Seller absorbs the 0.9-unit rounding residual; total still 100.
        val split = calculator(
            platformFeeRate = 0.10,
            seed = listOf(CreatorRevenue(item, creator, 0.11)),
        ).split(item, 100L)

        assertThat(split.platformAmount).isEqualTo(10L)
        assertThat(split.creatorAmount).isEqualTo(9L)
        assertThat(split.sellerAmount).isEqualTo(81L)
        assertThat(split.sellerAmount + (split.creatorAmount ?: 0L) + split.platformAmount)
            .isEqualTo(100L)
    }

    @Test
    fun `zero platform fee routes everything to seller plus creator`() {
        val split = calculator(
            platformFeeRate = 0.0,
            seed = listOf(CreatorRevenue(item, creator, 0.25)),
        ).split(item, 1000L)

        assertThat(split.platformAmount).isEqualTo(0L)
        assertThat(split.creatorAmount).isEqualTo(250L)
        assertThat(split.sellerAmount).isEqualTo(750L)
    }

    @Test
    fun `zero creator share leaves creator with zero and seller with the remainder`() {
        // Creator record exists but with share=0.0 — we still report
        // creatorId+creatorAmount=0 (record present), distinguishing from the
        // no-record path which returns null.
        val split = calculator(
            platformFeeRate = 0.10,
            seed = listOf(CreatorRevenue(item, creator, 0.0)),
        ).split(item, 1000L)

        assertThat(split.creatorAmount).isEqualTo(0L)
        assertThat(split.creatorId).isEqualTo(creator)
        assertThat(split.sellerAmount).isEqualTo(900L)
        assertThat(split.platformAmount).isEqualTo(100L)
    }

    @Test
    fun `default platform fee is 10pct`() {
        assertThat(CreatorRevenueCalculator.DEFAULT_PLATFORM_FEE).isEqualTo(0.10)
        val repo = InMemoryCreatorRevenueRepository()
        val split = CreatorRevenueCalculator(repo).split(ItemId("anything"), 1000L)
        assertThat(split.platformAmount).isEqualTo(100L)
    }
}
