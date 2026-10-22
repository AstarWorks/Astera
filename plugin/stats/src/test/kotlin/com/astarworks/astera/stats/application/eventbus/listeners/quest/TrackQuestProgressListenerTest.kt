package com.astarworks.astera.stats.application.eventbus.listeners.quest

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.contents.QuestChainContents
import com.astarworks.astera.core.application.port.inbound.IQuestService
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.advancement.AdvancementCriteria
import com.astarworks.astera.core.domain.combat.CombatContext
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.event.MobKilled
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.quest.PlayerQuestProgress
import com.astarworks.astera.core.domain.quest.QuestChain
import com.astarworks.astera.core.domain.quest.QuestChainId
import com.astarworks.astera.core.domain.quest.QuestError
import com.astarworks.astera.core.domain.quest.QuestStage
import com.astarworks.astera.core.domain.quest.QuestStageId
import com.astarworks.astera.core.domain.quest.QuestStageOutcome
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** ADR-0210: quests start on join and advance only from events the current stage's criteria ask for. */
class TrackQuestProgressListenerTest {

    private val player = PlayerId(UUID.randomUUID())
    private val victim = PlayerId(UUID.randomUUID())

    private class RecordingQuests(val active: MutableList<PlayerQuestProgress> = mutableListOf()) : IQuestService {
        val started = mutableListOf<QuestChainId>()
        val progressed = mutableListOf<Pair<QuestChainId, Int>>()
        override fun startChain(playerId: PlayerId, chainId: QuestChainId): Result<PlayerQuestProgress, QuestError> {
            started += chainId
            return Result.failure(QuestError.AlreadyStarted(chainId))
        }
        override fun progressCurrent(playerId: PlayerId, chainId: QuestChainId, increment: Int): Result<QuestStageOutcome, QuestError> {
            progressed += chainId to increment
            return Result.failure(QuestError.NotStarted(chainId))
        }
        override fun activeChains(playerId: PlayerId): Collection<PlayerQuestProgress> = active
        override fun allChains(playerId: PlayerId): Collection<PlayerQuestProgress> = active
    }

    private fun chain(id: String, criteria: AdvancementCriteria) = QuestChain(
        id = QuestChainId(id),
        titleKey = MessageKey("astera.quest.$id.title"),
        stages = listOf(
            QuestStage(
                id = QuestStageId("s1"),
                titleKey = MessageKey("astera.quest.$id.s1.title"),
                descriptionKey = MessageKey("astera.quest.$id.s1.description"),
                criteria = criteria,
            ),
        ),
    )

    private val chains = QuestChainContents().also {
        it.register(chain("hunt", AdvancementCriteria.KillCount(5)))
        it.register(chain("winner", AdvancementCriteria.MatchesWon(1)))
    }

    private fun row(id: String) = PlayerQuestProgress(player, QuestChainId(id), QuestStageId("s1"), 0)

    @Test
    fun `a kill advances only the chain whose current stage counts kills`() {
        val quests = RecordingQuests(mutableListOf(row("hunt"), row("winner")))
        val listener = TrackQuestProgressListener(quests, chains, InMemoryMatchRepository())

        listener.handle(PlayerKilled(killer = player, victim = victim, source = DamageSource.Environment.Generic, atTick = 0L))

        assertThat(quests.progressed).containsExactly(QuestChainId("hunt") to 1)
    }

    @Test
    fun `a kill with no killer advances nothing`() {
        val quests = RecordingQuests(mutableListOf(row("hunt")))
        val listener = TrackQuestProgressListener(quests, chains, InMemoryMatchRepository())

        listener.handle(PlayerKilled(killer = null, victim = victim, source = DamageSource.Environment.Generic, atTick = 0L))

        assertThat(quests.progressed).isEmpty()
    }

    @Test
    fun `kills and mob kills outside a match advance nothing (ADR-0235)`() {
        for (context in listOf(CombatContext.Training, CombatContext.Lobby, CombatContext.World)) {
            val quests = RecordingQuests(mutableListOf(row("hunt")))
            val listener = TrackQuestProgressListener(quests, chains, InMemoryMatchRepository())
            listener.handle(PlayerKilled(killer = player, victim = victim, source = DamageSource.Environment.Generic, atTick = 0L, context = context))
            listener.handle(
                MobKilled(
                    handle = EntityHandle(UUID.randomUUID(), "bukkit"),
                    mobTypeId = MobTypeId("bukkit:zombie"),
                    killerId = player,
                    drops = emptyList(),
                    atTick = 0L,
                    context = context,
                ),
            )
            assertThat(quests.progressed).describedAs("$context").isEmpty()
        }
    }

    @Test
    fun `a match kill still advances the hunt chain`() {
        val quests = RecordingQuests(mutableListOf(row("hunt")))
        val listener = TrackQuestProgressListener(quests, chains, InMemoryMatchRepository())
        listener.handle(
            PlayerKilled(killer = player, victim = victim, source = DamageSource.Environment.Generic, atTick = 0L, context = CombatContext.Match(MatchId("m-1"))),
        )
        assertThat(quests.progressed).containsExactly(QuestChainId("hunt") to 1)
    }

    @Test
    fun `joining puts the player on every chain`() {
        val quests = RecordingQuests()

        StartQuestsOnJoinListener(quests, chains).handle(PlayerJoinedServer(player))

        assertThat(quests.started).containsExactlyInAnyOrder(QuestChainId("hunt"), QuestChainId("winner"))
    }
}
