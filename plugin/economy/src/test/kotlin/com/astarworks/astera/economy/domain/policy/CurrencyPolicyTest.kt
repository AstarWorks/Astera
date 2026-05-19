package com.astarworks.astera.economy.domain.policy

import com.astarworks.astera.core.domain.economy.Currency
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CurrencyPolicyTest {

    @Test
    fun `expirable=true without expirationDays throws`() {
        assertThatThrownBy {
            CurrencyPolicy(
                currency = Currency.GEM,
                expirable = true,
                expirationDays = null,
                circulationCap = null,
                redeemable = false,
                tradable = false,
                sourceClass = SourceClass.Purchased,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("expirable")
    }

    @Test
    fun `expirable=true with non-positive expirationDays throws`() {
        assertThatThrownBy {
            CurrencyPolicy(
                currency = Currency.GEM,
                expirable = true,
                expirationDays = 0,
                circulationCap = null,
                redeemable = false,
                tradable = false,
                sourceClass = SourceClass.Purchased,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `circulationCap must be positive`() {
        assertThatThrownBy {
            CurrencyPolicy(
                currency = Currency.GEM,
                expirable = false,
                expirationDays = null,
                circulationCap = 0L,
                redeemable = false,
                tradable = false,
                sourceClass = SourceClass.Purchased,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("circulationCap")
    }

    @Test
    fun `circulationCap negative throws`() {
        assertThatThrownBy {
            CurrencyPolicy(
                currency = Currency.GEM,
                expirable = false,
                expirationDays = null,
                circulationCap = -1L,
                redeemable = false,
                tradable = false,
                sourceClass = SourceClass.Purchased,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `JP prepaid-value policy is constructable`() {
        // JP rule: 180-day expiry, ¥10M circulation cap, non-redeemable, non-tradable.
        val policy = CurrencyPolicy(
            currency = Currency.GEM,
            expirable = true,
            expirationDays = 180,
            circulationCap = 10_000_000L,
            redeemable = false,
            tradable = false,
            sourceClass = SourceClass.Purchased,
        )
        assertThat(policy.expirationDays).isEqualTo(180)
        assertThat(policy.circulationCap).isEqualTo(10_000_000L)
        assertThat(policy.redeemable).isFalse()
        assertThat(policy.tradable).isFalse()
    }

    @Test
    fun `Earned policy with no cap and no expiry is constructable`() {
        val policy = CurrencyPolicy(
            currency = Currency.GOLD,
            expirable = false,
            expirationDays = null,
            circulationCap = null,
            redeemable = false,
            tradable = true,
            sourceClass = SourceClass.Earned,
        )
        assertThat(policy.expirable).isFalse()
        assertThat(policy.tradable).isTrue()
    }
}
