package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.core.domain.combat.CombatContext
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.event.MobKilled
import com.astarworks.astera.core.domain.event.SkillXpGained
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.norma.Norma
import com.astarworks.astera.core.domain.norma.NormaId
import com.astarworks.astera.core.domain.norma.NormaReward
import com.astarworks.astera.core.domain.norma.NormaTarget
import com.astarworks.astera.core.domain.norma.PeriodKind
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.progression.SkillXpCurve
import com.astarworks.astera.core.domain.stats.PlayerStats
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.stats.application.eventbus.listeners.progression.AwardBossSlayerXpOnMobKilledListener
import com.astarworks.astera.stats.application.eventbus.listeners.progression.AwardCombatXpOnDamageDealtListener
import com.astarworks.astera.stats.application.service.InMemoryAchievementRepository
import com.astarworks.astera.stats.application.service.InMemoryLevelService
import com.astarworks.astera.stats.application.service.InMemoryNormaService
import com.astarworks.astera.stats.application.service.InMemoryPlayerStatsRepository
import com.astarworks.astera.stats.application.service.progression.InMemorySkillProgressionRepository
import com.astarworks.astera.stats.application.service.progression.InMemorySkillXpCurveRegistry
import com.astarworks.astera.stats.application.usecase.achievement.UnlockAchievementUseCase
import com.astarworks.astera.stats.application.usecase.level.GrantXpUseCase
import com.astarworks.astera.stats.application.usecase.progression.AwardSkillXpUseCase
import com.astarworks.astera.stats.application.usecase.stats.RecordKillUseCase
import com.astarworks.astera.stats.domain.achievement.Achievement
import com.astarworks.astera.stats.domain.event.MissionCompleted
import com.astarworks.astera.stats.domain.event.XpGranted
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * ADR-0237: every progression / stat / kill-credit consumer counts a combat event only when it carries a
 * `Match` context. Training dummies, lobby brawls and wild mobs leave no trace; a match event pays as before.
 */
class ProgressionContextGatingTest {

    private val attacker = PlayerId(UUID.randomUUID())
    private val target = PlayerId(UUID.randomUUID())
    private val nonCounting = listOf(CombatContext.Training, CombatContext.Lobby, CombatContext.World)
    private val inMatch = CombatContext.Match(MatchId("m-1"))

    private fun damage(context: CombatContext, amount: Double = 600.0) = DamageDealt(
        attacker = attacker,
        target = target,
        resolved = ResolvedDamage(amount, DamageAttribute.PHYSICAL, wasBlocked = false, wasCritical = false),
        source = DamageSource.Environment.Generic,
        atTick = 0L,
        context = context,
    )

    private fun kill(context: CombatContext) = PlayerKilled(
        victim = target,
        killer = attacker,
        source = DamageSource.Environment.Generic,
        atTick = 1L,
        context = context,
    )

    // --- stats + kill XP -----------------------------------------------------------------

    private fun rewardOnKill(): Triple<InMemoryPlayerStatsRepository, FakeDomainEventBus, RewardOnKillListener> {
        val repo = InMemoryPlayerStatsRepository()
        val bus = FakeDomainEventBus()
        return Triple(repo, bus, RewardOnKillListener(RecordKillUseCase(repo, bus), GrantXpUseCase(InMemoryLevelService(), bus)))
    }

    @Test
    fun `a kill outside a match records no kill and grants no XP`() {
        for (context in nonCounting) {
            val (repo, bus, listener) = rewardOnKill()
            listener.handle(kill(context))
            assertThat(repo.find(attacker)).describedAs("$context").isNull()
            assertThat(bus.publishedOfType<XpGranted>()).describedAs("$context").isEmpty()
        }
    }

    @Test
    fun `a match kill records the kill and grants 100 XP`() {
        val (repo, bus, listener) = rewardOnKill()
        listener.handle(kill(inMatch))
        assertThat(repo.find(attacker)!!.kills).isEqualTo(1L)
        assertThat(bus.publishedOfType<XpGranted>().single().amount).isEqualTo(100L)
    }

    @Test
    fun `damage outside a match leaves the damage stats alone, damage in a match counts`() {
        for (context in nonCounting) {
            val repo = InMemoryPlayerStatsRepository()
            RewardOnDamageListener(repo).handle(damage(context))
            assertThat(repo.find(attacker)).describedAs("$context").isNull()
            assertThat(repo.find(target)).describedAs("$context").isNull()
        }
        val repo = InMemoryPlayerStatsRepository()
        RewardOnDamageListener(repo).handle(damage(inMatch, 25.0))
        assertThat(repo.find(attacker)!!.damageDealt).isEqualTo(25L)
    }

    // --- missions ---------------------------------------------------------------------------

    private fun norma(id: String, target: NormaTarget) = Norma(
        id = NormaId(id),
        target = target,
        reward = NormaReward("STAR", 10, 5),
        period = PeriodKind.Daily,
        displayKey = MessageKey("astera.norma.$id.name"),
    )

    @Test
    fun `kill and damage missions ignore events outside a match`() {
        for (context in nonCounting) {
            val bus = FakeDomainEventBus()
            val service = InMemoryNormaService(listOf(norma("kill-1", NormaTarget.Kills(1)), norma("damage-100", NormaTarget.Damage(100))))
            MissionProgressOnKillListener(service, bus).handle(kill(context))
            TrackNormaProgressListener(service, bus, InMemoryMatchRepository()).handle(damage(context))
            assertThat(bus.publishedOfType<MissionCompleted>()).describedAs("$context").isEmpty()
        }
    }

    @Test
    fun `kill and damage missions complete on match events`() {
        val bus = FakeDomainEventBus()
        val service = InMemoryNormaService(listOf(norma("kill-1", NormaTarget.Kills(1)), norma("damage-100", NormaTarget.Damage(100))))
        MissionProgressOnKillListener(service, bus).handle(kill(inMatch))
        TrackNormaProgressListener(service, bus, InMemoryMatchRepository()).handle(damage(inMatch))
        assertThat(bus.publishedOfType<MissionCompleted>().map { it.normaId })
            .containsExactlyInAnyOrder(NormaId("kill-1"), NormaId("damage-100"))
    }

    // --- achievements ---------------------------------------------------------------------

    private fun achievementSetup(): Triple<InMemoryPlayerStatsRepository, InMemoryAchievementRepository, UnlockAchievementUseCase> {
        val stats = InMemoryPlayerStatsRepository()
        val achievements = InMemoryAchievementRepository()
        achievements.registerAll(
            listOf("kills-10", "damage-1k").map {
                Achievement(
                    id = AchievementId(it),
                    nameKey = MessageKey("astera.stats.achievement.$it.name"),
                    descriptionKey = MessageKey("astera.stats.achievement.$it.description"),
                    iconItemId = "minecraft:iron_sword",
                )
            },
        )
        stats.upsert(PlayerStats.of(attacker, kills = 50L, damageDealt = 5_000L))
        return Triple(stats, achievements, UnlockAchievementUseCase(achievements, FakeDomainEventBus()))
    }

    @Test
    fun `milestone achievements do not unlock from events outside a match`() {
        for (context in nonCounting) {
            val (stats, achievements, unlock) = achievementSetup()
            UnlockAchievementOnKillMilestoneListener(unlock, stats).handle(kill(context))
            UnlockAchievementOnDamageMilestoneListener(unlock, stats).handle(damage(context))
            assertThat(achievements.unlocked(attacker)).describedAs("$context").isEmpty()
        }
    }

    @Test
    fun `milestone achievements unlock from match events`() {
        val (stats, achievements, unlock) = achievementSetup()
        UnlockAchievementOnKillMilestoneListener(unlock, stats).handle(kill(inMatch))
        UnlockAchievementOnDamageMilestoneListener(unlock, stats).handle(damage(inMatch))
        assertThat(achievements.unlocked(attacker)).contains(AchievementId("kills-10"), AchievementId("damage-1k"))
    }

    // --- skill XP -------------------------------------------------------------------------------

    private fun awardSkillXp(bus: FakeDomainEventBus) = AwardSkillXpUseCase(
        InMemorySkillProgressionRepository(),
        InMemorySkillXpCurveRegistry(defaultCurve = SkillXpCurve.exponential()),
        bus,
    )

    private fun mobKilled(context: CombatContext) = MobKilled(
        handle = EntityHandle(id = UUID.randomUUID(), backendId = "bukkit"),
        mobTypeId = MobTypeId("bukkit:zombie"),
        killerId = attacker,
        drops = emptyList(),
        atTick = 1L,
        context = context,
    )

    @Test
    fun `combat and boss slayer XP are only paid for match events`() {
        for (context in nonCounting) {
            val bus = FakeDomainEventBus()
            AwardCombatXpOnDamageDealtListener(awardSkillXp(bus)).handle(damage(context))
            AwardBossSlayerXpOnMobKilledListener(awardSkillXp(bus)).handle(mobKilled(context))
            assertThat(bus.publishedOfType<SkillXpGained>()).describedAs("$context").isEmpty()
        }
        val bus = FakeDomainEventBus()
        AwardCombatXpOnDamageDealtListener(awardSkillXp(bus)).handle(damage(inMatch, 10.0))
        AwardBossSlayerXpOnMobKilledListener(awardSkillXp(bus)).handle(mobKilled(inMatch))
        assertThat(bus.publishedOfType<SkillXpGained>()).hasSize(2)
    }
}
