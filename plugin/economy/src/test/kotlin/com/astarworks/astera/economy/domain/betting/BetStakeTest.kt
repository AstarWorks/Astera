package com.astarworks.astera.economy.domain.betting

import com.astarworks.astera.core.domain.economy.Currency
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

internal class BetStakeTest {

    @Test
    fun `accepts a STAR stake`() {
        val stake = BetStake(Currency.STAR, 100L)
        assertThat(stake.currency).isEqualTo(Currency.STAR)
        assertThat(stake.amount).isEqualTo(100L)
    }

    @Test
    fun `accepts a GOLD stake`() {
        val stake = BetStake(Currency.GOLD, 1L)
        assertThat(stake.currency).isEqualTo(Currency.GOLD)
    }

    @Test
    fun `rejects zero amount`() {
        assertThatThrownBy { BetStake(Currency.GOLD, 0L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("stake amount must be > 0")
    }

    @Test
    fun `rejects negative amount`() {
        assertThatThrownBy { BetStake(Currency.GOLD, -5L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("stake amount must be > 0")
    }

    @Test
    fun `rejects GEM stake per JP compliance`() {
        assertThatThrownBy { BetStake(Currency.GEM, 100L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Currency.GEM is not eligible for betting")
    }
}
