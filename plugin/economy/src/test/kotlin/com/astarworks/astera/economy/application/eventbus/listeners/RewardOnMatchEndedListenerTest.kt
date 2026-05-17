package com.astarworks.astera.economy.application.eventbus.listeners

import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.service.InMemoryCurrencyLedger
import com.astarworks.astera.economy.application.usecase.currency.DepositUseCase
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.economy.domain.event.CurrencyChanged
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.domain.event.MatchEnded
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class RewardOnMatchEndedListenerTest {

    private lateinit var ledger: InMemoryCurrencyLedger
    private lateinit var matchRepo: InMemoryMatchRepository
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var depositUc: DepositUseCase

    private val winner1 = PlayerId(UUID.randomUUID())
    private val winner2 = PlayerId(UUID.randomUUID())
    private val loser1 = PlayerId(UUID.randomUUID())
    private val teamA = TeamId("red")
    private val teamB = TeamId("blue")
    private val matchId = MatchId("m-3")
    private val stageId = StageId("arena")

    @BeforeEach
    fun setUp() {
        ledger = InMemoryCurrencyLedger()
        matchRepo = InMemoryMatchRepository()
        eventBus = FakeDomainEventBus()
        depositUc = DepositUseCase(ledger, eventBus)
    }

    private fun team(id: TeamId, members: Set<PlayerId>) = Team(
        id = id,
        displayNameKey = MessageKey("astera.team.${id.value}.name"),
        colorKey = MessageKey("astera.team.${id.value}.color"),
        members = members,
    )

    private fun match(teams: List<Team>) = Match(
        id = matchId,
        phase = MatchPhase.ENDED,
        teams = teams,
        score = Score.EMPTY,
        stageId = stageId,
        startedAtTick = 0L,
    )

    private fun eventForWinner(winner: TeamId?) = MatchEnded(
        matchId = matchId,
        winningTeam = winner,
        atTick = 100L,
        reason = if (winner != null) EndReason.ScoreThresholdReached else EndReason.TimerExpired,
    )

    @Test
    fun `credits every member of the winning team and publishes one CurrencyChanged each`() {
        matchRepo.save(match(listOf(team(teamA, setOf(winner1, winner2)), team(teamB, setOf(loser1)))))
        val listener = RewardOnMatchEndedListener(matchRepo, depositUc, rewardAmount = 100L)
        listener.handle(eventForWinner(teamA))

        assertThat(ledger.balance(winner1, Currency.STAR)).isEqualTo(100L)
        assertThat(ledger.balance(winner2, Currency.STAR)).isEqualTo(100L)
        assertThat(ledger.balance(loser1, Currency.STAR)).isEqualTo(0L)

        val events = eventBus.publishedOfType<CurrencyChanged>()
        assertThat(events).hasSize(2)
        assertThat(events).allMatch { it.reason == CurrencyChangeReason.MatchReward }
        assertThat(events).allMatch { it.delta == 100L }
    }

    @Test
    fun `draw (null winningTeam) pays nothing`() {
        matchRepo.save(match(listOf(team(teamA, setOf(winner1)), team(teamB, setOf(loser1)))))
        val listener = RewardOnMatchEndedListener(matchRepo, depositUc, rewardAmount = 100L)
        listener.handle(eventForWinner(null))

        assertThat(ledger.balance(winner1, Currency.STAR)).isEqualTo(0L)
        assertThat(ledger.balance(loser1, Currency.STAR)).isEqualTo(0L)
        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `rewardAmount = 0 pays nothing even when winner exists`() {
        matchRepo.save(match(listOf(team(teamA, setOf(winner1)))))
        val listener = RewardOnMatchEndedListener(matchRepo, depositUc, rewardAmount = 0L)
        listener.handle(eventForWinner(teamA))

        assertThat(ledger.balance(winner1, Currency.STAR)).isEqualTo(0L)
        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `negative rewardAmount pays nothing (guarded before deposit call)`() {
        matchRepo.save(match(listOf(team(teamA, setOf(winner1)))))
        val listener = RewardOnMatchEndedListener(matchRepo, depositUc, rewardAmount = -50L)
        listener.handle(eventForWinner(teamA))

        // listener short-circuits on rewardAmount <= 0 BEFORE deposit, so no
        // InvalidAmount failure event either
        assertThat(ledger.balance(winner1, Currency.STAR)).isEqualTo(0L)
        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `match not found in repo is a no-op`() {
        // matchRepo empty
        val listener = RewardOnMatchEndedListener(matchRepo, depositUc, rewardAmount = 100L)
        listener.handle(eventForWinner(teamA))

        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `winningTeam id present but no matching team in match is a no-op for payout`() {
        matchRepo.save(match(listOf(team(teamA, setOf(winner1)))))
        val listener = RewardOnMatchEndedListener(matchRepo, depositUc, rewardAmount = 100L)
        listener.handle(eventForWinner(TeamId("phantom")))

        assertThat(ledger.balance(winner1, Currency.STAR)).isEqualTo(0L)
        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `currency override is honoured (GOLD instead of STAR)`() {
        matchRepo.save(match(listOf(team(teamA, setOf(winner1)))))
        val listener = RewardOnMatchEndedListener(matchRepo, depositUc, rewardAmount = 25L, rewardCurrency = Currency.GOLD)
        listener.handle(eventForWinner(teamA))

        assertThat(ledger.balance(winner1, Currency.GOLD)).isEqualTo(25L)
        assertThat(ledger.balance(winner1, Currency.STAR)).isEqualTo(0L)
        val ev = eventBus.publishedOfType<CurrencyChanged>().single()
        assertThat(ev.currency).isEqualTo(Currency.GOLD)
    }
}
