package com.astarworks.astera.economy.application.usecase.betting

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.BettingMatchInitiated
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.service.betting.InMemoryBettingHistoryService
import com.astarworks.astera.economy.application.service.betting.InMemoryBettingMatchRepository
import com.astarworks.astera.economy.application.service.betting.InMemoryBettingOptInProvider
import com.astarworks.astera.economy.application.service.betting.InMemoryBettingPolicyRegistry
import com.astarworks.astera.economy.domain.betting.BetStake
import com.astarworks.astera.economy.domain.betting.BettingError
import com.astarworks.astera.economy.domain.betting.BettingPolicy
import com.astarworks.astera.economy.domain.betting.LossStreakState
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal class InitiateBettingChallengeUseCaseTest {

    private val now: Instant = Instant.parse("2026-05-24T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val policy = BettingPolicy.testing()
    private val challenger = PlayerId(UUID.randomUUID())
    private val opponent = PlayerId(UUID.randomUUID())
    private val stake = BetStake(Currency.GOLD, 50L)

    private val matches = InMemoryBettingMatchRepository()
    private val policies = InMemoryBettingPolicyRegistry(listOf(policy))
    private val history = InMemoryBettingHistoryService(matches, policy.cooldownAfterMatch.inWholeMilliseconds)
    private val optIn = InMemoryBettingOptInProvider()
    private val eventBus = FakeDomainEventBus()

    private val uc = InitiateBettingChallengeUseCase(matches, policies, history, optIn, eventBus, clock)

    @BeforeEach
    fun setUp() {
        optIn.setOptedIn(challenger, true)
        optIn.setOptedIn(opponent, true)
    }

    @Test
    fun `happy path produces a PendingAcceptance and publishes BettingMatchInitiated`() {
        val res = uc.execute(challenger, opponent, policy.id, stake)
        assertThat(res.isSuccess).isTrue()
        val match = res.getOrNull()!!
        assertThat(match.challengerId).isEqualTo(challenger)
        assertThat(match.opponentId).isEqualTo(opponent)
        assertThat(match.stake).isEqualTo(stake)

        val events = eventBus.published.filterIsInstance<BettingMatchInitiated>()
        assertThat(events).hasSize(1)
        assertThat(events.single().bettingMatchUuid).isEqualTo(match.id.value)
        assertThat(events.single().stakeAmount).isEqualTo(50L)
    }

    @Test
    fun `self-betting is rejected`() {
        val res = uc.execute(challenger, challenger, policy.id, stake)
        assertThat(res.errorOrNull()).isInstanceOf(BettingError.SelfBettingForbidden::class.java)
    }

    @Test
    fun `unknown policy is rejected`() {
        val res = uc.execute(
            challenger, opponent,
            com.astarworks.astera.economy.domain.betting.BettingPolicyId("missing"),
            stake,
        )
        assertThat(res.errorOrNull()).isInstanceOf(BettingError.UnknownPolicy::class.java)
    }

    @Test
    fun `GEM stake is rejected at the BetStake boundary`() {
        org.assertj.core.api.Assertions.assertThatThrownBy {
            BetStake(Currency.GEM, 50L)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `stake exceeding per-match cap is rejected`() {
        val big = BetStake(Currency.GOLD, policy.maxStakePerMatch + 1L)
        val res = uc.execute(challenger, opponent, policy.id, big)
        assertThat(res.errorOrNull()).isInstanceOf(BettingError.StakeExceedsCap::class.java)
    }

    @Test
    fun `challenger opt-out is rejected`() {
        optIn.setOptedIn(challenger, false)
        val res = uc.execute(challenger, opponent, policy.id, stake)
        val err = res.errorOrNull() as? BettingError.PlayerOptedOut
        assertThat(err?.playerId).isEqualTo(challenger)
    }

    @Test
    fun `opponent opt-out is rejected`() {
        optIn.setOptedIn(opponent, false)
        val res = uc.execute(challenger, opponent, policy.id, stake)
        val err = res.errorOrNull() as? BettingError.PlayerOptedOut
        assertThat(err?.playerId).isEqualTo(opponent)
    }

    @Test
    fun `cooldown rejects within window`() {
        history.recordResolution(challenger, now.minusMillis(10L))
        val res = uc.execute(challenger, opponent, policy.id, stake)
        assertThat(res.errorOrNull()).isInstanceOf(BettingError.CooldownActive::class.java)
    }

    @Test
    fun `loss-streak cooldown rejects`() {
        var state = LossStreakState.empty(challenger)
        repeat(policy.lossStreakLimit) {
            state = state.applyLoss(now, policy.lossStreakLimit, policy.lossStreakCooldown.inWholeMilliseconds)
        }
        history.saveLossStreak(state)
        val res = uc.execute(challenger, opponent, policy.id, stake)
        assertThat(res.errorOrNull()).isInstanceOf(BettingError.LossStreakLimitReached::class.java)
    }

    @Test
    fun `daily cap rejects on the would-exceed boundary`() {
        // Saturate one bet just under the cap, then attempt one more that exceeds.
        // We seed by saving an accepted (Active) match into the repo.
        val priorStake = BetStake(Currency.GOLD, policy.perCurrencyDailyStakeCap - stake.amount + 1L)
        val seeded = com.astarworks.astera.economy.domain.betting.BettingMatch.initiate(
            id = com.astarworks.astera.economy.domain.betting.BettingMatchId.mint(),
            policy = policy,
            challengerId = challenger,
            opponentId = opponent,
            stake = priorStake,
            now = now.minusSeconds(60),
        ).accept(now.minusSeconds(50), com.astarworks.astera.core.domain.match.MatchId("bet-prior"))
        matches.save(seeded)

        val res = uc.execute(challenger, opponent, policy.id, stake)
        assertThat(res.errorOrNull()).isInstanceOf(BettingError.DailyCapWouldBeExceeded::class.java)
    }
}
