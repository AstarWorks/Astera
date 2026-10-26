package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.inbound.IQuestService
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerUnlockView
import com.astarworks.astera.core.domain.achievement.TitleId
import com.astarworks.astera.core.domain.advancement.AdvancementId
import com.astarworks.astera.core.domain.advancement.AdvancementReward
import com.astarworks.astera.core.domain.event.AdvancementRewardGranted
import com.astarworks.astera.core.application.port.outbound.loginbonus.ILoginBonusService
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.LoginBonusClaimed
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.norma.NormaId
import com.astarworks.astera.core.domain.quest.QuestChainId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.stats.application.port.outbound.IAchievementRepository
import com.astarworks.astera.stats.application.port.outbound.INormaService
import com.astarworks.astera.stats.domain.event.MissionCompleted
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** ADR-0210: join -> daily bonus, kills/wins -> missions + quests + achievements, all on the shipped content. */
class ProgressionLoopScenarioTest {

    @Test
    fun `player and environmental kills both record the victim death through production wiring`() {
        scenario("stats-deaths") {
            val killer = player("killer")
            val victim = player("victim")
            val bus = sim.services.getOrThrow<IDomainEventBus>()
            val stats = sim.services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IPlayerStatsRepository>()
            bus.publish(PlayerKilled(victim.id, killer.id, DamageSource.Environment.Generic, 1L))
            bus.publish(PlayerKilled(victim.id, null, DamageSource.Environment.Generic, 2L))
            assertThat(stats.find(victim.id)!!.deaths).isEqualTo(2L)
            assertThat(stats.find(killer.id)!!.kills).isEqualTo(1L)
        }
    }

    @Test
    fun `joining the lobby collects the daily bonus once and starts the streak`() {
        scenario("progression-login-bonus") {
            val alice = player("alice")
            val ledger = sim.services.getOrThrow<ICurrencyLedger>()
            val before = ledger.balance(alice.id, Currency.GOLD)

            advance(110)

            assertThat(ledger.balance(alice.id, Currency.GOLD)).isEqualTo(before + 10L)
            assertThat(eventsOf<LoginBonusClaimed>().single().streakDays).isEqualTo(1)
            val streak = sim.services.getOrThrow<ILoginBonusService>().streakOf(alice.id)!!
            assertThat(streak.streakDays).isEqualTo(1)
            assertThat(streak.lastClaimDate).isEqualTo(LocalDate.now())
            assertThat(sim.server.view(alice.id)!!.messages.joinToString("\n")).contains("Daily bonus")

            // A second claim the same day pays nothing.
            advance(200)
            assertThat(ledger.balance(alice.id, Currency.GOLD)).isEqualTo(before + 10L)
        }
    }

    @Test
    fun `winning a match completes the daily victory mission, unlocks achievements and moves the quest`() {
        scenario("progression-win-loop") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            advance(110)
            val matchId = startMatch("skirmish", mapOf(red to "red", blue to "blue"))
            val bus = sim.services.getOrThrow<IDomainEventBus>()

            repeat(5) { bus.publish(PlayerKilled(blue.id, red.id, DamageSource.Environment.Fall(1.0), 1L)) }
            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.TeamVictory(TeamId("red")))
            advance(5)

            val completed = eventsOf<MissionCompleted>().filter { it.playerId == red.id }.map { it.normaId }
            assertThat(completed).contains(NormaId("daily-victory"), NormaId("daily-killer-1"))
            assertThat(eventsOf<MissionCompleted>().filter { it.playerId == blue.id }).isEmpty()

            val achievements = sim.services.getOrThrow<IAchievementRepository>()
            assertThat(achievements.unlocked(red.id)).contains(AchievementId("wins-1"), AchievementId("mission-first"))
            assertThat(achievements.unlocked(blue.id)).doesNotContain(AchievementId("wins-1"))

            // tutorial-chain: stage 1 (5 kills) cleared by the five kills, so the player now sits on stage 2.
            val chain = sim.services.getOrThrow<IQuestService>().allChains(red.id)
                .first { it.chainId == QuestChainId("tutorial-chain") }
            assertThat(chain.currentStageId.value).isEqualTo("win-match")

            // The mission screen shows the state.
            val views = sim.services.getOrThrow<INormaService>().views(red.id)
            assertThat(views.first { it.norma.id == NormaId("daily-victory") }.completed).isTrue
        }
    }
    @Test
    fun `a quest title reward becomes an owned title`() {
        scenario("progression-quest-title") {
            val alice = player("alice")
            advance(5)
            val bus = sim.services.getOrThrow<IDomainEventBus>()
            // The same fan-out event GrantQuestRewardUseCase publishes for a `grant_title` stage reward.
            bus.publish(
                AdvancementRewardGranted(alice.id, AdvancementId("quest-tutorial-chain-win-match"), 0, AdvancementReward.GrantTitle(TitleId("rookie")), 1L),
            )
            assertThat(sim.services.getOrThrow<IPlayerUnlockView>().unlocks(alice.id)).contains("title:rookie")
        }
    }
}
