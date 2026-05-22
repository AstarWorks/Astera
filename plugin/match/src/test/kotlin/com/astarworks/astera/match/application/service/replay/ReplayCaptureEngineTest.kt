package com.astarworks.astera.match.application.service.replay

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.event.ChatMessageSent
import com.astarworks.astera.core.domain.event.MatchRecordingStarted
import com.astarworks.astera.core.domain.event.MatchRecordingStopped
import com.astarworks.astera.core.domain.chat.ChatChannel
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.replay.DamageSourceKind
import com.astarworks.astera.core.domain.replay.ReplayError
import com.astarworks.astera.core.domain.replay.ReplayEvent
import com.astarworks.astera.core.domain.replay.ReplayFormat
import com.astarworks.astera.core.domain.replay.Vec3Snapshot
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.match.domain.event.PhaseChanged
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class ReplayCaptureEngineTest {

    private lateinit var bus: FakeDomainEventBus
    private lateinit var scheduler: FakeScheduler
    private lateinit var repo: InMemoryMatchReplayRepository
    private lateinit var stateProvider: TestRecorderStateProvider
    private lateinit var engine: ReplayCaptureEngine

    private val matchId = MatchId("match-1")
    private val attacker = PlayerId(UUID.randomUUID())
    private val victim = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        bus = FakeDomainEventBus()
        scheduler = FakeScheduler(startTick = 0L)
        repo = InMemoryMatchReplayRepository()
        stateProvider = TestRecorderStateProvider()
        engine = ReplayCaptureEngine(bus, scheduler, repo, stateProvider)
        engine.bootstrap()
    }

    @Test
    fun `startRecording publishes MatchRecordingStarted and is recorded as active`() {
        engine.startRecording(matchId, ReplayFormat.EventLogKeyFrame())

        assertThat(engine.isRecording(matchId)).isTrue
        assertThat(engine.activeRecordings()).containsExactly(matchId)
        val started = bus.published.filterIsInstance<MatchRecordingStarted>()
        assertThat(started).hasSize(1)
        assertThat(started[0].matchId).isEqualTo(matchId)
        assertThat(started[0].formatDescriptor).isEqualTo("event-log-keyframe")
    }

    @Test
    fun `DamageDealt published while recording becomes a ReplayEvent DamageDealt`() {
        engine.startRecording(matchId, ReplayFormat.EventLogKeyFrame())
        bus.publish(
            DamageDealt(
                attacker = attacker,
                target = victim,
                resolved = ResolvedDamage(
                    finalAmount = 7.5,
                    attribute = DamageAttribute.PHYSICAL,
                    wasBlocked = false,
                    wasCritical = true,
                ),
                source = DamageSource.Item(ItemId("example-sword"), attacker),
                atTick = 10L,
            ),
        )

        val result = engine.stopRecording(matchId)
        check(result is Result.Success)
        val damageEvents = result.value.events.filterIsInstance<ReplayEvent.DamageDealt>()
        assertThat(damageEvents).hasSize(1)
        assertThat(damageEvents[0].amount).isEqualTo(7.5)
        assertThat(damageEvents[0].sourceKind).isEqualTo(DamageSourceKind.ITEM)
        assertThat(damageEvents[0].attackerId).isEqualTo(attacker)
        assertThat(damageEvents[0].victimId).isEqualTo(victim)
    }

    @Test
    fun `multiple event variants are captured`() {
        engine.startRecording(matchId, ReplayFormat.EventLogKeyFrame())

        bus.publish(
            PlayerKilled(
                victim = victim, killer = attacker,
                source = DamageSource.Environment.Generic, atTick = 5L,
            ),
        )
        bus.publish(SkillCast(caster = attacker, skillId = SkillId("fireball"), at = Vec3.ZERO, atTick = 6L))
        bus.publish(ChatMessageSent(sender = attacker, channel = ChatChannel.Global, content = "hi"))
        bus.publish(PhaseChanged(matchId, from = MatchPhase.COUNTDOWN, to = MatchPhase.ACTIVE, atTick = 7L))

        val result = engine.stopRecording(matchId)
        check(result is Result.Success)
        val events = result.value.events
        assertThat(events.filterIsInstance<ReplayEvent.PlayerKilled>()).hasSize(1)
        assertThat(events.filterIsInstance<ReplayEvent.SkillCast>()).hasSize(1)
        assertThat(events.filterIsInstance<ReplayEvent.ChatSent>()).hasSize(1)
        assertThat(events.filterIsInstance<ReplayEvent.MatchPhaseChanged>()).hasSize(1)
    }

    @Test
    fun `stopRecording without start returns NotRecording`() {
        val result = engine.stopRecording(MatchId("ghost"))
        check(result is Result.Failure)
        assertThat(result.error).isInstanceOf(ReplayError.NotRecording::class.java)
    }

    @Test
    fun `stopRecording with no buffered data returns EmptyRecording`() {
        engine.startRecording(matchId, ReplayFormat.EventLogKeyFrame())
        val result = engine.stopRecording(matchId)
        check(result is Result.Failure)
        assertThat(result.error).isInstanceOf(ReplayError.EmptyRecording::class.java)
    }

    @Test
    fun `stopRecording publishes MatchRecordingStopped and saves to repository`() {
        engine.startRecording(matchId, ReplayFormat.EventLogKeyFrame())
        bus.publish(
            PlayerKilled(
                victim = victim, killer = null,
                source = DamageSource.Environment.Fall(5.0), atTick = 1L,
            ),
        )
        scheduler.advanceTicks(100L)

        val result = engine.stopRecording(matchId)
        check(result is Result.Success)
        val stopped = bus.published.filterIsInstance<MatchRecordingStopped>()
        assertThat(stopped).hasSize(1)
        assertThat(stopped[0].matchId).isEqualTo(matchId)
        assertThat(stopped[0].eventCount).isGreaterThanOrEqualTo(1)
        assertThat(stopped[0].endTick).isEqualTo(100L)

        val stored = repo.findByMatchId(matchId)
        assertThat(stored).isNotNull
        assertThat(stored!!.events.filterIsInstance<ReplayEvent.PlayerKilled>()).hasSize(1)
    }

    @Test
    fun `key-frame tick captures snapshot from state provider`() {
        stateProvider.participants[matchId] = listOf(attacker, victim)
        stateProvider.positions[attacker] = Vec3Snapshot(1f, 64f, 2f)
        stateProvider.positions[victim] = Vec3Snapshot(3f, 64f, 4f)
        stateProvider.healths[attacker] = 20.0
        stateProvider.healths[victim] = 15.0
        stateProvider.items[attacker] = ItemId("example-sword")

        engine.startRecording(matchId, ReplayFormat.EventLogKeyFrame(keyFramePer = 20))
        scheduler.advanceTicks(40L)

        val result = engine.stopRecording(matchId)
        check(result is Result.Success)
        val frames = result.value.keyFrames
        assertThat(frames).isNotEmpty
        val firstWithData = frames.first { it.playerPositions.isNotEmpty() }
        assertThat(firstWithData.playerPositions).containsKeys(attacker, victim)
        assertThat(firstWithData.playerHealth[attacker]).isEqualTo(20.0)
        assertThat(firstWithData.playerActiveItems[attacker]).isEqualTo(ItemId("example-sword"))
        assertThat(firstWithData.playerActiveItems).doesNotContainKey(victim)
    }

    @Test
    fun `startRecording is idempotent on the same matchId`() {
        engine.startRecording(matchId, ReplayFormat.EventLogKeyFrame())
        engine.startRecording(matchId, ReplayFormat.FullPerTick(maxDurationTicks = 100))

        assertThat(engine.activeRecordings()).containsExactly(matchId)
        // Two MatchRecordingStarted events, but the active map has one entry
        val started = bus.published.filterIsInstance<MatchRecordingStarted>()
        assertThat(started).hasSize(2)
    }

    @Test
    fun `shutdown drops subscriptions so subsequent events are not captured`() {
        engine.startRecording(matchId, ReplayFormat.EventLogKeyFrame())
        engine.shutdown()

        bus.publish(
            PlayerKilled(victim, attacker, DamageSource.Environment.Generic, atTick = 1L),
        )
        // The buffer was cleared by shutdown; isRecording is false.
        assertThat(engine.isRecording(matchId)).isFalse
    }

    @Test
    fun `re-recording the same match overwrites the prior row in the repository`() {
        engine.startRecording(matchId, ReplayFormat.EventLogKeyFrame())
        bus.publish(PlayerKilled(victim, attacker, DamageSource.Environment.Generic, atTick = 1L))
        val firstResult = engine.stopRecording(matchId) as Result.Success
        val firstId = firstResult.value.id

        engine.startRecording(matchId, ReplayFormat.EventLogKeyFrame())
        bus.publish(PlayerKilled(victim, attacker, DamageSource.Environment.Generic, atTick = 50L))
        val secondResult = engine.stopRecording(matchId) as Result.Success
        val secondId = secondResult.value.id

        assertThat(secondId).isNotEqualTo(firstId)
        assertThat(repo.findById(firstId)).isNull()
        assertThat(repo.findById(secondId)).isNotNull
        assertThat(repo.findByMatchId(matchId)?.id).isEqualTo(secondId)
    }
}

private class TestRecorderStateProvider :
    com.astarworks.astera.match.application.port.outbound.IRecorderStateProvider {
    val participants: MutableMap<MatchId, List<PlayerId>> = mutableMapOf()
    val positions: MutableMap<PlayerId, Vec3Snapshot> = mutableMapOf()
    val healths: MutableMap<PlayerId, Double> = mutableMapOf()
    val items: MutableMap<PlayerId, ItemId> = mutableMapOf()

    override fun participantsOf(matchId: MatchId): Collection<PlayerId> =
        participants[matchId] ?: emptyList()
    override fun positionOf(playerId: PlayerId): Vec3Snapshot? = positions[playerId]
    override fun healthOf(playerId: PlayerId): Double? = healths[playerId]
    override fun activeItemOf(playerId: PlayerId): ItemId? = items[playerId]
}
