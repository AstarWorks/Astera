package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.port.outbound.IEntityDamageGateway
import com.astarworks.astera.combat.application.service.training.TrainingDummy
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.inbound.IQuestService
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.combat.CombatContext
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.event.MobKilled
import com.astarworks.astera.core.domain.event.SkillXpGained
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.stats.application.port.outbound.IAchievementRepository
import com.astarworks.astera.stats.application.port.outbound.ILevelService
import com.astarworks.astera.stats.application.port.outbound.INormaService
import com.astarworks.astera.stats.domain.event.AchievementUnlocked
import com.astarworks.astera.stats.domain.event.MissionCompleted
import com.astarworks.astera.stats.domain.event.XpGranted
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * ADR-0237: sandbag hits, lobby brawls and wild mob kills leave XP, GOLD, missions, quests, achievements,
 * stats and the kill feed untouched; a PvP kill between match participants moves them.
 */
class CombatContextProgressionScenarioTest {

    private val lobby = WorldId("lobby")

    /** Everything the progression loop could have changed for [id]. */
    private data class Snapshot(
        val gold: Long,
        val xp: Long,
        val kills: Long,
        val damageDealt: Long,
        val damageTaken: Long,
        val missions: List<Pair<String, Long>>,
        val quests: List<Triple<String, String, Int>>,
        val achievements: Set<String>,
        val progressionEvents: Int,
        val messages: Int,
    )

    private fun ScenarioScope.snapshot(entity: SimEntity): Snapshot {
        val id = entity.id
        val stats = sim.services.getOrThrow<IPlayerStatsRepository>().find(id)
        return Snapshot(
            gold = sim.services.getOrThrow<ICurrencyLedger>().balance(id, Currency.GOLD),
            xp = sim.services.getOrThrow<ILevelService>().totalXp(id).value,
            kills = stats?.kills ?: 0L,
            damageDealt = stats?.damageDealt ?: 0L,
            damageTaken = stats?.damageTaken ?: 0L,
            missions = sim.services.getOrThrow<INormaService>().views(id).map { it.norma.id.value to it.current },
            quests = sim.services.getOrThrow<IQuestService>().allChains(id)
                .map { Triple(it.chainId.value, it.currentStageId.value, it.stageProgress) },
            achievements = sim.services.getOrThrow<IAchievementRepository>().unlocked(id).map { it.value }.toSet(),
            progressionEvents = eventsOf<XpGranted>().size + eventsOf<SkillXpGained>().size +
                eventsOf<MissionCompleted>().size + eventsOf<AchievementUnlocked>().size + eventsOf<CurrencyChanged>().size,
            messages = checkNotNull(sim.server.view(id)).messages.size,
        )
    }

    private fun ScenarioScope.hit(attacker: SimEntity, target: PlayerId, amount: Double) = checkNotNull(sim.combat.applyDamage).execute(
        DamageAttempt(
            targetId = target,
            baseProfile = DamageProfile.single(amount, DamageAttribute.PHYSICAL),
            source = DamageSource.Item(ItemId("pistol"), attacker.id),
        ),
    )

    @Test
    fun `hitting a training dummy down to its HP floor changes nothing but the dummy`() {
        scenario("context-training-dummy") {
            sim.trainingShells.worldLoaded = true
            val plan = sim.services.getOrThrow<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyTrainingRange>().plan
            val shooter = player("shooter", at = plan.spawn)
            shooter.world = lobby
            advance(110) // dummies stand up, the daily login bonus lands and settles
            val body = checkNotNull(sim.trainingShells.bodyOf("range-10")).id
            val before = snapshot(shooter)

            repeat(40) { hit(shooter, body, 40.0) } // 1600 damage into a 500 HP dummy, many times over
            advance(5)

            val hits = eventsOf<DamageDealt>().filter { it.target == body }
            assertThat(hits).hasSize(40)
            assertThat(hits.map { it.context }.toSet()).containsExactly(CombatContext.Training)
            assertThat(sim.services.getOrThrow<IEntityDamageGateway>().hitPoints(body)).isEqualTo(TrainingDummy.MIN_HP)
            assertThat(eventsOf<PlayerKilled>()).isEmpty()
            assertThat(snapshot(shooter)).isEqualTo(before)
        }
    }

    @Test
    fun `a lobby brawl and a lobby mob kill outside a match pay nothing`() {
        scenario("context-lobby-brawl") {
            val brawler = player("brawler", at = Vec3(0.5, 65.0, 6.5))
            val victim = player("victim", at = Vec3(0.5, 65.0, 16.5))
            brawler.world = lobby
            victim.world = lobby
            advance(110)
            val before = snapshot(brawler)

            hit(brawler, victim.id, 500.0)
            advance(5)
            sim.services.getOrThrow<IDomainEventBus>().publish(
                MobKilled(
                    handle = EntityHandle(UUID.randomUUID(), "bukkit"),
                    mobTypeId = MobTypeId("bukkit:zombie"),
                    killerId = brawler.id,
                    drops = emptyList(),
                    atTick = 1L,
                    context = CombatContext.World,
                ),
            )
            advance(5)

            val kill = eventsOf<PlayerKilled>().single()
            assertThat(kill.killer).isEqualTo(brawler.id)
            assertThat(kill.context).isEqualTo(CombatContext.Lobby)
            assertThat(snapshot(brawler)).isEqualTo(before)
        }
    }

    @Test
    fun `a PvP kill between match participants moves stats, XP, missions and the kill feed but pays no GOLD`() {
        scenario("context-match-kill") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            advance(110)
            startMatch("battle-loyal", mapOf(red to "red", blue to "blue"))
            val before = snapshot(red)

            hit(red, blue.id, 500.0)
            advance(5)

            val kill = eventsOf<PlayerKilled>().single()
            assertThat(kill.context).isInstanceOf(CombatContext.Match::class.java)
            assertThat(eventsOf<DamageDealt>().single().context).isInstanceOf(CombatContext.Match::class.java)
            val after = snapshot(red)
            assertThat(after.kills).isEqualTo(before.kills + 1)
            assertThat(after.damageDealt).isGreaterThan(before.damageDealt)
            assertThat(after.xp).isGreaterThan(before.xp)
            assertThat(after.missions).isNotEqualTo(before.missions)
            assertThat(after.messages).describedAs("kill feed line").isGreaterThan(before.messages)
            // XP never converts to a currency: only the match-end table pays GOLD.
            assertThat(after.gold).isEqualTo(before.gold)
        }
    }
}
