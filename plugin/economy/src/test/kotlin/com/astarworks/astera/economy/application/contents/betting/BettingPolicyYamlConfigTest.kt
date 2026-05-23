package com.astarworks.astera.economy.application.contents.betting

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.economy.application.config.betting.BettingPolicyYamlConfig
import com.charleskorn.kaml.Yaml
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

internal class BettingPolicyYamlConfigTest {

    private val sample = """
        id: default
        max_stake_per_match: 1000
        per_currency_daily_stake_cap: 10000
        cooldown_after_match_ms: 600000
        acceptance_timeout_ms: 600000
        loss_streak_limit: 5
        loss_streak_cooldown_ms: 86400000
        allowed_currencies:
          - star
          - gold
        payout_split: 1.0
    """.trimIndent()

    @Test
    fun `decodes and materialises the default policy`() {
        val dto = Yaml.default.decodeFromString(BettingPolicyYamlConfig.serializer(), sample)
        val policy = dto.toDomain()
        assertThat(policy.id.value).isEqualTo("default")
        assertThat(policy.maxStakePerMatch).isEqualTo(1_000L)
        assertThat(policy.perCurrencyDailyStakeCap).isEqualTo(10_000L)
        assertThat(policy.cooldownAfterMatch).isEqualTo(10.minutes)
        assertThat(policy.acceptanceTimeout).isEqualTo(10.minutes)
        assertThat(policy.lossStreakCooldown).isEqualTo(24.hours)
        assertThat(policy.allowedCurrencies).containsExactlyInAnyOrder(Currency.STAR, Currency.GOLD)
    }

    @Test
    fun `unknown currency raises informative error`() {
        val bad = sample.replace("- gold", "- bitcoin")
        val dto = Yaml.default.decodeFromString(BettingPolicyYamlConfig.serializer(), bad)
        assertThatThrownBy { dto.toDomain() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Unknown currency 'bitcoin'")
    }

    @Test
    fun `currency parsing is case-insensitive`() {
        val mixed = sample.replace("- gold", "- GOLD").replace("- star", "- Star")
        val dto = Yaml.default.decodeFromString(BettingPolicyYamlConfig.serializer(), mixed)
        val policy = dto.toDomain()
        assertThat(policy.allowedCurrencies).containsExactlyInAnyOrder(Currency.STAR, Currency.GOLD)
    }
}
