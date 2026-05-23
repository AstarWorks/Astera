package com.astarworks.astera.economy.domain.betting

import com.astarworks.astera.core.domain.economy.Currency
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal class BettingPolicyTest {

    @Test
    fun `DEFAULT is a usable conservative configuration`() {
        val p = BettingPolicy.DEFAULT
        assertThat(p.id.value).isEqualTo("default")
        assertThat(p.maxStakePerMatch).isEqualTo(1_000L)
        assertThat(p.perCurrencyDailyStakeCap).isEqualTo(10_000L)
        assertThat(p.cooldownAfterMatch).isEqualTo(10.minutes)
        assertThat(p.acceptanceTimeout).isEqualTo(10.minutes)
        assertThat(p.lossStreakLimit).isEqualTo(5)
        assertThat(p.lossStreakCooldown).isEqualTo(24.hours)
        assertThat(p.allowedCurrencies).containsExactlyInAnyOrder(Currency.STAR, Currency.GOLD)
        assertThat(p.payoutSplit).isEqualTo(1.0)
    }

    @Test
    fun `rejects GEM in allowedCurrencies`() {
        assertThatThrownBy {
            BettingPolicy.testing(allowedCurrencies = setOf(Currency.STAR, Currency.GEM))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Currency.GEM is not eligible for betting")
    }

    @Test
    fun `rejects empty allowedCurrencies`() {
        assertThatThrownBy { BettingPolicy.testing(allowedCurrencies = emptySet()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("allowedCurrencies must not be empty")
    }

    @Test
    fun `rejects daily cap below per-match cap`() {
        assertThatThrownBy {
            BettingPolicy.testing(maxStakePerMatch = 500L, perCurrencyDailyStakeCap = 100L)
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("perCurrencyDailyStakeCap")
    }

    @Test
    fun `rejects payoutSplit greater than 1`() {
        assertThatThrownBy { BettingPolicy.testing(payoutSplit = 1.01) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("payoutSplit")
    }

    @Test
    fun `rejects payoutSplit of 0`() {
        assertThatThrownBy { BettingPolicy.testing(payoutSplit = 0.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("payoutSplit")
    }

    @Test
    fun `rejects non-positive acceptanceTimeout`() {
        assertThatThrownBy { BettingPolicy.testing(acceptanceTimeout = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("acceptanceTimeout")
    }

    @Test
    fun `accepts mid-range payoutSplit for house cut`() {
        val p = BettingPolicy.testing(payoutSplit = 0.95)
        assertThat(p.payoutSplit).isEqualTo(0.95)
    }

    @Test
    fun `testing factory honours overrides`() {
        val p = BettingPolicy.testing(
            maxStakePerMatch = 50L,
            perCurrencyDailyStakeCap = 100L,
            cooldownAfterMatch = 5.seconds,
        )
        assertThat(p.maxStakePerMatch).isEqualTo(50L)
        assertThat(p.perCurrencyDailyStakeCap).isEqualTo(100L)
        assertThat(p.cooldownAfterMatch).isEqualTo(5.seconds)
    }
}

internal class BettingPolicyIdTest {

    @Test
    fun `accepts lower-kebab-case`() {
        val id = BettingPolicyId("custom-pool")
        assertThat(id.value).isEqualTo("custom-pool")
    }

    @Test
    fun `rejects uppercase`() {
        assertThatThrownBy { BettingPolicyId("Default") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects underscore`() {
        assertThatThrownBy { BettingPolicyId("my_policy") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects empty string`() {
        assertThatThrownBy { BettingPolicyId("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
