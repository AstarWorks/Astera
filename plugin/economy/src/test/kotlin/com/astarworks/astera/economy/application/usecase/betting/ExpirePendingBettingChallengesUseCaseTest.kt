package com.astarworks.astera.economy.application.usecase.betting

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.service.betting.InMemoryBettingMatchRepository
import com.astarworks.astera.economy.domain.betting.BetStake
import com.astarworks.astera.economy.domain.betting.BettingMatch
import com.astarworks.astera.economy.domain.betting.BettingMatchId
import com.astarworks.astera.economy.domain.betting.BettingPolicy
import com.astarworks.astera.economy.domain.betting.BettingStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal class ExpirePendingBettingChallengesUseCaseTest {

    private val now: Instant = Instant.parse("2026-05-24T12:00:00Z")
    private val policy = BettingPolicy.testing()
    private val challenger = PlayerId(UUID.randomUUID())
    private val opponent = PlayerId(UUID.randomUUID())
    private val stake = BetStake(Currency.GOLD, 50L)

    @Test
    fun `expires pending matches whose deadline has passed`() {
        val matches = InMemoryBettingMatchRepository()
        val past = BettingMatch.initiate(BettingMatchId.mint(), policy, challenger, opponent, stake,
            now.minusSeconds(120))
        val future = BettingMatch.initiate(BettingMatchId.mint(), policy, challenger, opponent, stake, now)
        matches.save(past)
        matches.save(future)

        val uc = ExpirePendingBettingChallengesUseCase(matches, Clock.fixed(now, ZoneOffset.UTC))
        val expired = uc.execute()

        assertThat(expired).hasSize(1)
        assertThat(expired.single().id).isEqualTo(past.id)
        assertThat(matches.findById(past.id)!!.status).isEqualTo(BettingStatus.Expired)
        assertThat(matches.findById(future.id)!!.status).isEqualTo(BettingStatus.PendingAcceptance)
    }
}
