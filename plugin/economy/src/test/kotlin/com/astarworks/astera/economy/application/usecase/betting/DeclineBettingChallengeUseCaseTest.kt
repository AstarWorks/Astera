package com.astarworks.astera.economy.application.usecase.betting

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.service.betting.InMemoryBettingMatchRepository
import com.astarworks.astera.economy.domain.betting.BetStake
import com.astarworks.astera.economy.domain.betting.BettingError
import com.astarworks.astera.economy.domain.betting.BettingMatch
import com.astarworks.astera.economy.domain.betting.BettingMatchId
import com.astarworks.astera.economy.domain.betting.BettingPolicy
import com.astarworks.astera.economy.domain.betting.BettingStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class DeclineBettingChallengeUseCaseTest {

    private val now: Instant = Instant.parse("2026-05-24T12:00:00Z")
    private val policy = BettingPolicy.testing()
    private val challenger = PlayerId(UUID.randomUUID())
    private val opponent = PlayerId(UUID.randomUUID())
    private val stake = BetStake(Currency.GOLD, 50L)

    private val matches = InMemoryBettingMatchRepository()
    private val uc = DeclineBettingChallengeUseCase(matches)

    private fun pending(): BettingMatch = matches.save(
        BettingMatch.initiate(BettingMatchId.mint(), policy, challenger, opponent, stake, now),
    )

    @Test
    fun `opponent can decline`() {
        val pending = pending()
        val res = uc.execute(pending.id, opponent)
        assertThat(res.isSuccess).isTrue()
        assertThat(matches.findById(pending.id)!!.status).isEqualTo(BettingStatus.Declined)
    }

    @Test
    fun `challenger can rescind`() {
        val pending = pending()
        val res = uc.execute(pending.id, challenger)
        assertThat(res.isSuccess).isTrue()
        assertThat(matches.findById(pending.id)!!.status).isEqualTo(BettingStatus.Declined)
    }

    @Test
    fun `stranger cannot decline`() {
        val pending = pending()
        val res = uc.execute(pending.id, PlayerId(UUID.randomUUID()))
        assertThat(res.errorOrNull()).isInstanceOf(BettingError.NotInvited::class.java)
    }

    @Test
    fun `unknown match`() {
        val res = uc.execute(BettingMatchId.mint(), opponent)
        assertThat(res.errorOrNull()).isInstanceOf(BettingError.UnknownMatch::class.java)
    }
}
