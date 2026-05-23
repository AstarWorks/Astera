package com.astarworks.astera.economy.application.config.betting

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.economy.domain.betting.BettingPolicy
import com.astarworks.astera.economy.domain.betting.BettingPolicyId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds

/**
 * YAML-shaped DTO for `content/bet-policies/<id>.yaml` (Wave 5bet / ADR-0103).
 *
 * Kept separate from [BettingPolicy] so the domain stays free of
 * serialization annotations (Konsist enforces this; same pattern as
 * `ShopYamlConfig`, `RefinementYamlConfig`, …).
 *
 * # Example
 *
 * ```yaml
 * id: default
 * max_stake_per_match: 1000
 * per_currency_daily_stake_cap: 10000
 * cooldown_after_match_ms: 600000          # 10 min
 * acceptance_timeout_ms: 600000            # 10 min
 * loss_streak_limit: 5
 * loss_streak_cooldown_ms: 86400000        # 24 h
 * allowed_currencies: [star, gold]
 * payout_split: 1.0
 * ```
 *
 * Durations are expressed in milliseconds (Long) to keep the wire format
 * primitive-only (kaml + numeric durations are not portable across the
 * three parsers we support — YAML / TOML / JSON).
 */
@Serializable
public data class BettingPolicyYamlConfig(
    val id: String,
    @SerialName("max_stake_per_match")
    val maxStakePerMatch: Long,
    @SerialName("per_currency_daily_stake_cap")
    val perCurrencyDailyStakeCap: Long,
    @SerialName("cooldown_after_match_ms")
    val cooldownAfterMatchMs: Long,
    @SerialName("acceptance_timeout_ms")
    val acceptanceTimeoutMs: Long,
    @SerialName("loss_streak_limit")
    val lossStreakLimit: Int,
    @SerialName("loss_streak_cooldown_ms")
    val lossStreakCooldownMs: Long,
    @SerialName("allowed_currencies")
    val allowedCurrencies: List<String>,
    @SerialName("payout_split")
    val payoutSplit: Double,
) {
    /** Materialise into the domain [BettingPolicy]. */
    public fun toDomain(): BettingPolicy = BettingPolicy(
        id = BettingPolicyId(id),
        maxStakePerMatch = maxStakePerMatch,
        perCurrencyDailyStakeCap = perCurrencyDailyStakeCap,
        cooldownAfterMatch = cooldownAfterMatchMs.milliseconds,
        acceptanceTimeout = acceptanceTimeoutMs.milliseconds,
        lossStreakLimit = lossStreakLimit,
        lossStreakCooldown = lossStreakCooldownMs.milliseconds,
        allowedCurrencies = allowedCurrencies.map { parseCurrency(it) }.toSet(),
        payoutSplit = payoutSplit,
    )

    private fun parseCurrency(raw: String): Currency {
        val key = raw.trim().uppercase()
        return Currency.entries.firstOrNull { it.name == key }
            ?: throw IllegalArgumentException(
                "Unknown currency '$raw' in bet policy '$id' " +
                    "(must be one of ${Currency.entries.joinToString { it.name.lowercase() }})",
            )
    }
}
