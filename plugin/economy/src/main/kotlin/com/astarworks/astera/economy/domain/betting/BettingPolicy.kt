package com.astarworks.astera.economy.domain.betting

import com.astarworks.astera.core.domain.economy.Currency
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Per-server tunable policy controlling betting safety constraints (Wave 5bet
 * / ADR-0103 §"Decision" + §"Constraints (compliance + safety)").
 *
 * Authored as `content/bet-policies/<id>.yaml` and loaded into
 * `IBettingPolicyRegistry`. The default policy id (per server bootstrap) is
 * `default` and is loaded from `content/bet-policies/default.yaml`.
 *
 * ## Constraint matrix (PoE Race-style fairness via cap + cooldown)
 *
 * | Field                       | Default          | Why                                                          |
 * |-----------------------------|------------------|--------------------------------------------------------------|
 * | [maxStakePerMatch]          | 1 000 GOLD      | OSRS Duel Arena 反例: 制限なし stake が RWT magnet を生んだ |
 * | [perCurrencyDailyStakeCap]  | 10 000 GOLD     | 1 日の上限 — 依存症抑制 (PoE Race "ladder cooldown" 流用) |
 * | [cooldownAfterMatch]        | 10 min          | 連続 betting 防止 (chase loss 抑制)                          |
 * | [acceptanceTimeout]         | 10 min          | 申請 timeout (ADR-0103 §"Workflow" step 2)                  |
 * | [lossStreakLimit]           | 5                | 連敗時の auto-disable 閾値                                  |
 * | [lossStreakCooldown]        | 24 h             | 連敗時の disable 期間                                       |
 * | [allowedCurrencies]         | STAR + GOLD      | GEM (paid premium) は domain で hard-rejected                |
 * | [payoutSplit]               | 1.0 (winner all) | Phase 1: house cut なし。Phase 4 で tunable に               |
 *
 * `payoutSplit < 1.0` means a fraction goes to the server vault (Phase 4 hook;
 * Phase 1 always 1.0). The pot is `2 × amount`; winner receives
 * `floor(2 × amount × payoutSplit)`.
 *
 * ## Out of scope (ADR-0103 §"Alternatives Considered")
 *
 * - Real-money redemption — explicitly rejected.
 * - Item stakes — also out of scope for Wave 5bet. OSRS Duel Arena ADR-0103
 *   §"Context" cites items as the principal RWT vector; the safer surface
 *   is currency-only.
 * - Multi-party / spectator betting — Phase 4+ backlog.
 */
public data class BettingPolicy(
    val id: BettingPolicyId,
    val maxStakePerMatch: Long,
    val perCurrencyDailyStakeCap: Long,
    val cooldownAfterMatch: Duration,
    val acceptanceTimeout: Duration,
    val lossStreakLimit: Int,
    val lossStreakCooldown: Duration,
    val allowedCurrencies: Set<Currency>,
    val payoutSplit: Double,
) {
    init {
        require(maxStakePerMatch > 0L) { "maxStakePerMatch must be > 0, got $maxStakePerMatch" }
        require(perCurrencyDailyStakeCap >= maxStakePerMatch) {
            "perCurrencyDailyStakeCap ($perCurrencyDailyStakeCap) must be >= maxStakePerMatch " +
                "($maxStakePerMatch); a single match would otherwise exceed the daily cap"
        }
        require(cooldownAfterMatch >= Duration.ZERO) { "cooldownAfterMatch must be >= 0" }
        require(acceptanceTimeout > Duration.ZERO) { "acceptanceTimeout must be > 0" }
        require(lossStreakLimit > 0) { "lossStreakLimit must be > 0, got $lossStreakLimit" }
        require(lossStreakCooldown >= Duration.ZERO) { "lossStreakCooldown must be >= 0" }
        require(allowedCurrencies.isNotEmpty()) { "allowedCurrencies must not be empty" }
        require(Currency.GEM !in allowedCurrencies) {
            "Currency.GEM is not eligible for betting (paid premium currency, " +
                "ADR-0103 / ADR-0035 JP compliance)"
        }
        require(payoutSplit > 0.0 && payoutSplit <= 1.0) {
            "payoutSplit must be in (0.0, 1.0], got $payoutSplit"
        }
    }

    public companion object {
        /** Conservative Phase 1 default. Tunable per-server via YAML. */
        public val DEFAULT: BettingPolicy = BettingPolicy(
            id = BettingPolicyId("default"),
            maxStakePerMatch = 1_000L,
            perCurrencyDailyStakeCap = 10_000L,
            cooldownAfterMatch = 10.minutes,
            acceptanceTimeout = 10.minutes,
            lossStreakLimit = 5,
            lossStreakCooldown = 24.hours,
            allowedCurrencies = setOf(Currency.STAR, Currency.GOLD),
            payoutSplit = 1.0,
        )

        /** Test helper: low caps + 1 s cooldowns for fast deterministic tests. */
        public fun testing(
            id: BettingPolicyId = BettingPolicyId("testing"),
            maxStakePerMatch: Long = 100L,
            perCurrencyDailyStakeCap: Long = 1_000L,
            cooldownAfterMatch: Duration = 1.seconds,
            acceptanceTimeout: Duration = 30.seconds,
            lossStreakLimit: Int = 3,
            lossStreakCooldown: Duration = 5.seconds,
            allowedCurrencies: Set<Currency> = setOf(Currency.STAR, Currency.GOLD),
            payoutSplit: Double = 1.0,
        ): BettingPolicy = BettingPolicy(
            id = id,
            maxStakePerMatch = maxStakePerMatch,
            perCurrencyDailyStakeCap = perCurrencyDailyStakeCap,
            cooldownAfterMatch = cooldownAfterMatch,
            acceptanceTimeout = acceptanceTimeout,
            lossStreakLimit = lossStreakLimit,
            lossStreakCooldown = lossStreakCooldown,
            allowedCurrencies = allowedCurrencies,
            payoutSplit = payoutSplit,
        )
    }
}

/** Identifier for a [BettingPolicy] — lower-kebab-case, matches YAML file stem. */
@JvmInline
public value class BettingPolicyId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "BettingPolicyId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
