package com.astarworks.astera.economy.application.usecase.betting

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.BettingMatchInitiated
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingHistoryService
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingMatchRepository
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingOptInProvider
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingPolicyRegistry
import com.astarworks.astera.economy.domain.betting.BetStake
import com.astarworks.astera.economy.domain.betting.BettingError
import com.astarworks.astera.economy.domain.betting.BettingMatch
import com.astarworks.astera.economy.domain.betting.BettingMatchId
import com.astarworks.astera.economy.domain.betting.BettingPolicyId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Validates + opens a new [BettingMatch] in [com.astarworks.astera.economy.domain.betting.BettingStatus.PendingAcceptance]
 * (Wave 5bet / ADR-0103 §"Workflow" step 1).
 *
 * Validation order — fail-fast on the first violation:
 *
 *   1. Self-betting   — challenger != opponent.
 *   2. Policy exists  — registry resolves [policyId].
 *   3. Currency       — `policy.allowedCurrencies` contains stake currency.
 *   4. Stake cap      — `stake.amount <= policy.maxStakePerMatch`.
 *   5. Opt-in         — both players are opted-in.
 *   6. Cooldown       — challenger is past `policy.cooldownAfterMatch`.
 *   7. Loss streak    — challenger is not in `lossStreakCooldown`.
 *   8. Daily cap      — challenger's day-to-date stake + amount <= `policy.perCurrencyDailyStakeCap`.
 *
 * The acceptance debit happens in [AcceptBettingChallengeUseCase] — this use
 * case only creates the record. No wallet movement until the opponent
 * accepts (mirrors OSRS Duel Arena 反例 — early debit pre-acceptance creates
 * lock-up windows that bots can grief).
 *
 * Publishes [BettingMatchInitiated] on success.
 */
public class InitiateBettingChallengeUseCase(
    private val matches: IBettingMatchRepository,
    private val policies: IBettingPolicyRegistry,
    private val history: IBettingHistoryService,
    private val optIn: IBettingOptInProvider,
    private val eventBus: IDomainEventBus,
    private val clock: Clock = Clock.systemUTC(),
) {

    public suspend fun execute(
        challengerId: PlayerId,
        opponentId: PlayerId,
        policyId: BettingPolicyId,
        stake: BetStake,
    ): Result<BettingMatch, BettingError> {
        val now = clock.instant()

        if (challengerId == opponentId) {
            return Result.failure(BettingError.SelfBettingForbidden(challengerId))
        }
        val policy = policies.get(policyId)
            ?: return Result.failure(BettingError.UnknownPolicy(policyId))
        if (stake.currency !in policy.allowedCurrencies) {
            return Result.failure(BettingError.CurrencyNotAllowed(stake.currency))
        }
        if (stake.amount > policy.maxStakePerMatch) {
            return Result.failure(BettingError.StakeExceedsCap(stake.amount, policy.maxStakePerMatch))
        }
        if (!optIn.isOptedIn(challengerId)) {
            return Result.failure(BettingError.PlayerOptedOut(challengerId))
        }
        if (!optIn.isOptedIn(opponentId)) {
            return Result.failure(BettingError.PlayerOptedOut(opponentId))
        }
        history.cooldownUntil(challengerId)?.let { until ->
            if (until.isAfter(now)) {
                return Result.failure(BettingError.CooldownActive(challengerId, until))
            }
        }
        val streak = history.lossStreakOf(challengerId)
        if (streak.isCoolingDown(now)) {
            return Result.failure(
                BettingError.LossStreakLimitReached(
                    playerId = challengerId,
                    streak = streak.consecutiveLosses,
                    limit = policy.lossStreakLimit,
                    cooldownEndsAt = checkNotNull(streak.cooldownEndsAt),
                ),
            )
        }

        val dayStart = startOfUtcDay(now)
        val usedToday = history.dailyStakeFor(challengerId, stake.currency, dayStart)
        if (usedToday + stake.amount > policy.perCurrencyDailyStakeCap) {
            return Result.failure(
                BettingError.DailyCapWouldBeExceeded(
                    playerId = challengerId,
                    currency = stake.currency,
                    usedToday = usedToday,
                    attempted = stake.amount,
                    cap = policy.perCurrencyDailyStakeCap,
                ),
            )
        }

        val match = BettingMatch.initiate(
            id = BettingMatchId.mint(),
            policy = policy,
            challengerId = challengerId,
            opponentId = opponentId,
            stake = stake,
            now = now,
        )
        val saved = matches.save(match)

        eventBus.publish(
            BettingMatchInitiated(
                bettingMatchUuid = saved.id.value,
                policyIdString = saved.policyId.value,
                challengerId = saved.challengerId,
                opponentId = saved.opponentId,
                currency = saved.stake.currency,
                stakeAmount = saved.stake.amount,
                acceptanceDeadline = saved.acceptanceDeadline,
            ),
        )
        return Result.success(saved)
    }

    private fun startOfUtcDay(now: Instant): Instant =
        now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
}
