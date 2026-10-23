package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.ILastMatchStore
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchRewardsGranted
import com.astarworks.astera.core.domain.event.MatchSummaryReady
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.match.result.MatchOutcome
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** F3-4: a match ends -> every player is paid from match-rewards.yaml once and sees the result. */
class MatchRewardsScenarioTest {

    @Test
    fun `match end pays winners and losers from the table and records the last match`() {
        scenario("match-rewards") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val matchId = startMatch("norma", mapOf(red to "red", blue to "blue"))
            val bus = sim.services.getOrThrow<IDomainEventBus>()
            val ledger = sim.services.getOrThrow<ICurrencyLedger>()
            val goldBefore = ledger.balance(red.id, Currency.GOLD) to ledger.balance(blue.id, Currency.GOLD)

            // red kills blue twice (kills/deaths tallied, red becomes MVP).
            repeat(2) { bus.publish(PlayerKilled(blue.id, red.id, DamageSource.Environment.Fall(1.0), 1L)) }

            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.TeamVictory(TeamId("red")))
            advance(5)

            val summary = eventsOf<MatchSummaryReady>().single().summary
            assertThat(summary.outcomeOf(red.id)).isEqualTo(MatchOutcome.WIN)
            assertThat(summary.mvp).isEqualTo(red.id)
            assertThat(summary.players.first { it.playerId == red.id }.kills).isEqualTo(2)
            assertThat(summary.players.first { it.playerId == blue.id }.deaths).isEqualTo(2)

            // norma uses the default rows: red = participation 10 + win 100 + 2 kills * 5 + mvp 35 GOLD;
            // blue = participation 10 + lose 35 GOLD.
            val granted = eventsOf<MatchRewardsGranted>().single().rewards
            assertThat(granted.getValue(red.id).gold).isEqualTo(175L)
            assertThat(granted.getValue(blue.id).gold).isEqualTo(45L)
            // The wallet also moves for reasons that are not the match table (kill-XP conversion, a daily
            // mission, the login bonus), so the match payout is read from the MatchReward deposits.
            fun matchGold(id: com.astarworks.astera.core.domain.player.PlayerId) =
                eventsOf<com.astarworks.astera.core.domain.event.CurrencyChanged>()
                    .filter { it.playerId == id && it.currency == Currency.GOLD && it.reason == com.astarworks.astera.core.domain.event.CurrencyChangeReason.MatchReward }
                    .sumOf { it.delta }
            assertThat(matchGold(red.id)).isEqualTo(175L)
            assertThat(matchGold(blue.id)).isEqualTo(45L)
            assertThat(ledger.balance(red.id, Currency.GOLD)).isGreaterThanOrEqualTo(goldBefore.first + 175L)
            assertThat(ledger.balance(blue.id, Currency.GOLD)).isGreaterThanOrEqualTo(goldBefore.second + 45L)

            // The result screen and the reward breakdown reached the players' chat.
            val redChat = sim.server.view(red.id)!!.messages.joinToString("\n")
            assertThat(redChat).contains("Top players").contains("Rewards").contains("+175")
            assertThat(sim.server.view(blue.id)!!.messages.joinToString("\n")).contains("Rewards")

            // Cross-server record for the lobby.
            val last = sim.services.getOrThrow<ILastMatchStore>().find(red.id)!!
            assertThat(last.outcome).isEqualTo(MatchOutcome.WIN)
            assertThat(last.kills).isEqualTo(2)
            assertThat(last.mvp).isTrue()
            assertThat(last.gold).isEqualTo(175L)

            // A repeated summary does not pay again.
            val settled = ledger.balance(red.id, Currency.GOLD)
            bus.publish(MatchSummaryReady(summary))
            assertThat(ledger.balance(red.id, Currency.GOLD)).isEqualTo(settled)
        }
    }
}
