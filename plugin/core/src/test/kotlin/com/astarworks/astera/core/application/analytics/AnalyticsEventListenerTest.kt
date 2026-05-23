package com.astarworks.astera.core.application.analytics

import com.astarworks.astera.core.application.port.outbound.analytics.IAnalyticsExporter
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.event.SkillCastRequested
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.PhaseOverrides
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AnalyticsEventListenerTest {

    private lateinit var exporter: RecordingAnalyticsExporter
    private lateinit var bus: FakeDomainEventBus
    private lateinit var listener: AnalyticsEventListener

    @BeforeEach
    fun setUp() {
        exporter = RecordingAnalyticsExporter()
        bus = FakeDomainEventBus()
        listener = AnalyticsEventListener(bus, exporter)
        listener.start()
    }

    @Test
    fun `MatchStarted then MatchEnded records duration histogram`() {
        val matchId = MatchId("test-match-1")
        val started = MatchStarted(
            matchId = matchId,
            stageId = StageId("arena"),
            atTick = 0L,
            occurredAt = Instant.parse("2026-05-20T00:00:00Z"),
        )
        val ended = MatchEnded(
            matchId = matchId,
            winningTeam = TeamId("red"),
            atTick = 1200L,
            reason = EndReason.TimerExpired,
            occurredAt = Instant.parse("2026-05-20T00:01:30Z"),
        )

        bus.publish(started)
        bus.publish(ended)

        assertThat(exporter.matchEnded).hasSize(1)
        val record = exporter.matchEnded.single()
        assertThat(record.matchId).isEqualTo("test-match-1")
        assertThat(record.durationSeconds).isEqualTo(90.0)
        assertThat(record.winningTeam).isEqualTo("red")
    }

    @Test
    fun `SkillCastRequested records skill cast counter`() {
        val event = SkillCastRequested(
            actorId = PlayerId(UUID.randomUUID()),
            skillId = SkillId("fireball"),
            origin = Vec3(0.0, 64.0, 0.0),
            sourceItemId = ItemId("wand"),
            cooldownOverrideTicks = null,
            phaseOverrides = PhaseOverrides.EMPTY,
        )

        bus.publish(event)

        assertThat(exporter.skillCasts).hasSize(1)
        assertThat(exporter.skillCasts.single().skillId).isEqualTo("fireball")
    }

    @Test
    fun `MatchEnded without MatchStarted records zero duration`() {
        val ended = MatchEnded(
            matchId = MatchId("orphan-match"),
            winningTeam = null,
            atTick = 200L,
            reason = EndReason.AdminEnded,
            occurredAt = Instant.now(),
        )
        bus.publish(ended)

        assertThat(exporter.matchEnded.single().durationSeconds).isEqualTo(0.0)
        assertThat(exporter.matchEnded.single().winningTeam).isEqualTo(null)
    }

    @Test
    fun `stop unsubscribes all listeners`() {
        listener.stop()

        bus.publish(SkillCastRequested(
            actorId = PlayerId(UUID.randomUUID()),
            skillId = SkillId("ignored"),
            origin = Vec3(0.0, 0.0, 0.0),
            sourceItemId = ItemId("none"),
            cooldownOverrideTicks = null,
        ))

        assertThat(exporter.skillCasts).isEmpty()
    }

    private class RecordingAnalyticsExporter : IAnalyticsExporter {
        data class MatchEndedRecord(
            val matchId: String,
            val durationSeconds: Double,
            val playerCount: Int,
            val winningTeam: String?,
        )

        data class SkillCastRecord(val skillId: String, val casterId: String)

        val matchEnded = mutableListOf<MatchEndedRecord>()
        val skillCasts = mutableListOf<SkillCastRecord>()

        override fun recordMatchEnded(
            matchId: String,
            durationSeconds: Double,
            playerCount: Int,
            winningTeam: String?,
        ) {
            matchEnded += MatchEndedRecord(matchId, durationSeconds, playerCount, winningTeam)
        }

        override fun recordKill(matchId: String, attackerId: String, victimId: String, itemId: String) = Unit
        override fun recordDeath(playerId: String, matchId: String) = Unit
        override fun recordPlayerSessionEnded(playerId: String, durationSeconds: Double) = Unit
        override fun recordSkillCast(skillId: String, casterId: String) {
            skillCasts += SkillCastRecord(skillId, casterId)
        }
        override fun recordShopRevenue(currency: String, amount: Long) = Unit
        override fun setMarketplaceListingCount(count: Long) = Unit
        override fun setMatchPlayerCount(matchId: String, count: Int) = Unit
        // Wave 5engage additions
        override fun setPlayerEngagementScore(playerId: String, score: Double) = Unit
        override fun setDauWauMau(dau: Long, wau: Long, mau: Long) = Unit
        override fun setRetentionCohort(cohort: String, day: String, rate: Double) = Unit
    }
}
