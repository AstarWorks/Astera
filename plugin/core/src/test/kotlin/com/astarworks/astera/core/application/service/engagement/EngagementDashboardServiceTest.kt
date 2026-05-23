package com.astarworks.astera.core.application.service.engagement

import com.astarworks.astera.core.application.port.outbound.analytics.IAnalyticsExporter
import com.astarworks.astera.core.domain.engagement.RetentionDay
import com.astarworks.astera.core.domain.event.EngagementSnapshotComputed
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class EngagementDashboardServiceTest {

    private lateinit var repo: InMemoryEngagementSnapshotRepository
    private lateinit var exporter: RecordingExporter
    private lateinit var bus: FakeDomainEventBus
    private lateinit var service: EngagementDashboardService

    private val now = Instant.parse("2026-05-21T00:00:00Z")

    @BeforeEach
    fun setUp() {
        repo = InMemoryEngagementSnapshotRepository()
        exporter = RecordingExporter()
        bus = FakeDomainEventBus()
        service = EngagementDashboardService(
            repository = repo,
            exporter = exporter,
            eventBus = bus,
            clock = { now },
            topNForGauges = 2,
        )
    }

    private fun pid(n: Int) =
        PlayerId(UUID.fromString("00000000-0000-0000-0000-${n.toString().padStart(12, '0')}"))

    @Test
    fun `recordPlayerActivity persists snapshot and publishes top-N gauge`() {
        service.recordPlayerActivity(
            playerId = pid(1),
            totalPlaytime = Duration.ofHours(50),
            loginStreakDays = 15,
            skillsExercisedCount = 5,
            refinementAttempts = 3,
            cosmeticEquipDiversity = 0.0,
            firstSeenAt = now.minus(Duration.ofDays(20)),
            lastSeenAt = now,
        )
        val stored = repo.find(pid(1))!!
        assertThat(stored.totalPlaytime).isEqualTo(Duration.ofHours(50))
        assertThat(stored.score.value).isEqualTo(0.6, within(0.001))
        // Top-N (=2) publishing fires the per-player gauge (loose comparison; double FP).
        val publishedScore = exporter.playerScores[pid(1).uuid.toString()]
        assertThat(publishedScore).isNotNull
        assertThat(publishedScore!!).isEqualTo(0.6, within(0.001))
    }

    @Test
    fun `compute emits DauWauMau gauges and EngagementSnapshotComputed event`() {
        // Three players: one seen today, one seen 3 days ago (=> dau no, wau yes),
        // one seen 10 days ago (=> dau no, wau no, mau yes).
        service.recordPlayerActivity(
            playerId = pid(1),
            totalPlaytime = Duration.ofHours(10),
            loginStreakDays = 1,
            skillsExercisedCount = 1,
            refinementAttempts = 0L,
            cosmeticEquipDiversity = 0.0,
            firstSeenAt = now.minus(Duration.ofDays(5)),
            lastSeenAt = now.minus(Duration.ofMinutes(10)),
        )
        service.recordPlayerActivity(
            playerId = pid(2),
            totalPlaytime = Duration.ofHours(5),
            loginStreakDays = 1,
            skillsExercisedCount = 1,
            refinementAttempts = 0L,
            cosmeticEquipDiversity = 0.0,
            firstSeenAt = now.minus(Duration.ofDays(5)),
            lastSeenAt = now.minus(Duration.ofDays(3)),
        )
        service.recordPlayerActivity(
            playerId = pid(3),
            totalPlaytime = Duration.ofHours(2),
            loginStreakDays = 1,
            skillsExercisedCount = 1,
            refinementAttempts = 0L,
            cosmeticEquipDiversity = 0.0,
            firstSeenAt = now.minus(Duration.ofDays(15)),
            lastSeenAt = now.minus(Duration.ofDays(10)),
        )

        val snapshot = service.compute()

        assertThat(snapshot.dauWauMau.dau).isEqualTo(1)
        assertThat(snapshot.dauWauMau.wau).isEqualTo(2)
        assertThat(snapshot.dauWauMau.mau).isEqualTo(3)
        assertThat(exporter.dauWauMau).isEqualTo(Triple(1L, 2L, 3L))

        // Top-N (=2) trims the result list.
        assertThat(snapshot.topEngaged).hasSize(2)
        assertThat(snapshot.topEngaged.map { it.score.value })
            .isSortedAccordingTo(Comparator.reverseOrder())

        // EngagementSnapshotComputed fired exactly once with the snapshot in the payload.
        val events = bus.published.filterIsInstance<EngagementSnapshotComputed>()
        assertThat(events).hasSize(1)
        assertThat(events.single().snapshot).isEqualTo(snapshot)
    }

    @Test
    fun `compute publishes one retention cohort per first-seen date`() {
        // Two players in the same cohort (first seen 2026-04-10 UTC), both
        // last-seen 30 days later → D1/D7/D30 should all be 1.0.
        val cohortStart = Instant.parse("2026-04-10T00:00:00Z")
        service.recordPlayerActivity(
            playerId = pid(1),
            totalPlaytime = Duration.ofHours(1),
            loginStreakDays = 1,
            skillsExercisedCount = 1,
            refinementAttempts = 0L,
            cosmeticEquipDiversity = 0.0,
            firstSeenAt = cohortStart,
            lastSeenAt = cohortStart.plus(Duration.ofDays(35)),
        )
        service.recordPlayerActivity(
            playerId = pid(2),
            totalPlaytime = Duration.ofHours(1),
            loginStreakDays = 1,
            skillsExercisedCount = 1,
            refinementAttempts = 0L,
            cosmeticEquipDiversity = 0.0,
            firstSeenAt = cohortStart,
            lastSeenAt = cohortStart.plus(Duration.ofDays(40)),
        )

        val snapshot = service.compute()
        assertThat(snapshot.retentionByCohort).hasSize(1)
        val byDay = snapshot.retentionByCohort.values.single()
        assertThat(byDay[RetentionDay.D1]!!.value).isEqualTo(1.0)
        assertThat(byDay[RetentionDay.D7]!!.value).isEqualTo(1.0)
        assertThat(byDay[RetentionDay.D30]!!.value).isEqualTo(1.0)
        // Exporter saw three cohort gauges, one per bin.
        assertThat(exporter.retentionCohorts).hasSize(3)
    }

    @Test
    fun `compute with no players yields empty snapshot but still fires event`() {
        val snapshot = service.compute()
        assertThat(snapshot.dauWauMau).isEqualTo(com.astarworks.astera.core.domain.engagement.DauWauMau.EMPTY)
        assertThat(snapshot.retentionByCohort).isEmpty()
        assertThat(snapshot.topEngaged).isEmpty()
        assertThat(bus.published.filterIsInstance<EngagementSnapshotComputed>()).hasSize(1)
    }

    private class RecordingExporter : IAnalyticsExporter {
        val playerScores = mutableMapOf<String, Double>()
        var dauWauMau: Triple<Long, Long, Long>? = null
        val retentionCohorts = mutableMapOf<String, Double>()

        override fun recordMatchEnded(matchId: String, durationSeconds: Double, playerCount: Int, winningTeam: String?) = Unit
        override fun recordKill(matchId: String, attackerId: String, victimId: String, itemId: String) = Unit
        override fun recordDeath(playerId: String, matchId: String) = Unit
        override fun recordPlayerSessionEnded(playerId: String, durationSeconds: Double) = Unit
        override fun recordSkillCast(skillId: String, casterId: String) = Unit
        override fun recordShopRevenue(currency: String, amount: Long) = Unit
        override fun setMarketplaceListingCount(count: Long) = Unit
        override fun setMatchPlayerCount(matchId: String, count: Int) = Unit

        override fun setPlayerEngagementScore(playerId: String, score: Double) {
            playerScores[playerId] = score
        }
        override fun setDauWauMau(dau: Long, wau: Long, mau: Long) {
            dauWauMau = Triple(dau, wau, mau)
        }
        override fun setRetentionCohort(cohort: String, day: String, rate: Double) {
            retentionCohorts["$cohort|$day"] = rate
        }
    }
}
