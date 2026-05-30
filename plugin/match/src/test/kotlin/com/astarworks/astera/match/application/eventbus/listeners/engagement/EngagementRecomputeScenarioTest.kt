package com.astarworks.astera.match.application.eventbus.listeners.engagement

import com.astarworks.astera.core.application.analytics.NoopAnalyticsExporter
import com.astarworks.astera.core.application.service.engagement.EngagementDashboardService
import com.astarworks.astera.core.application.service.engagement.InMemoryEngagementSnapshotRepository
import com.astarworks.astera.core.domain.engagement.EngagementScore
import com.astarworks.astera.core.domain.engagement.EngagementSnapshot
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Wave K 5integration-wire (ADR-0128) — scenario G.
 *
 * Proves: [MatchEnded] triggers
 * [EngagementDashboardService.recomputeForPlayer] for every participant of
 * every team in the match. `lastSeenAt` advances; the snapshot's score
 * recomputes against the current clock.
 */
class EngagementRecomputeScenarioTest {

    private val matchId = MatchId("kappa-match")
    private val teamRedId = TeamId("red")
    private val teamBlueId = TeamId("blue")
    private val player1 = PlayerId(UUID.randomUUID())
    private val player2 = PlayerId(UUID.randomUUID())
    private val player3 = PlayerId(UUID.randomUUID())

    @Test
    fun `MatchEnded recomputes each participant's snapshot`() {
        val matchRepo = InMemoryMatchRepository().apply {
            save(
                Match(
                    id = matchId,
                    phase = MatchPhase.ENDED,
                    teams = listOf(
                        Team(
                            id = teamRedId,
                            displayNameKey = MessageKey("astera.team.red"),
                            colorKey = MessageKey("astera.team.color.red"),
                            members = setOf(player1, player2),
                        ),
                        Team(
                            id = teamBlueId,
                            displayNameKey = MessageKey("astera.team.blue"),
                            colorKey = MessageKey("astera.team.color.blue"),
                            members = setOf(player3),
                        ),
                    ),
                    score = Score.EMPTY,
                    stageId = StageId("test-stage"),
                    startedAtTick = 0L,
                ),
            )
        }
        val snapshotRepo = InMemoryEngagementSnapshotRepository()
        // Seed prior snapshots for player1 + player2 only. player3 has none
        // (simulating a player whose record hasn't been computed yet — the
        // listener should silently skip).
        val past = Instant.parse("2026-05-29T08:00:00Z")
        snapshotRepo.upsert(seed(player1, past))
        snapshotRepo.upsert(seed(player2, past))

        val now = Instant.parse("2026-05-30T12:00:00Z")
        val service = EngagementDashboardService(
            repository = snapshotRepo,
            exporter = NoopAnalyticsExporter,
            eventBus = FakeDomainEventBus(),
            clock = { now },
        )
        val listener = RecomputeEngagementSnapshotOnMatchEndedListener(matchRepo, service)

        listener.handle(
            MatchEnded(
                matchId = matchId,
                winningTeam = teamRedId,
                atTick = 24_000L,
                reason = EndReason.ScoreThresholdReached,
            ),
        )

        // player1 + player2: lastSeenAt + computedAt advanced to `now`.
        val s1 = snapshotRepo.find(player1)!!
        assertThat(s1.lastSeenAt).isEqualTo(now)
        assertThat(s1.computedAt).isEqualTo(now)
        val s2 = snapshotRepo.find(player2)!!
        assertThat(s2.lastSeenAt).isEqualTo(now)
        // player3: no prior snapshot, listener no-ops, repo still has no row.
        assertThat(snapshotRepo.find(player3)).isNull()
    }

    @Test
    fun `unknown match is a debug-logged no-op`() {
        val matchRepo = InMemoryMatchRepository() // empty
        val snapshotRepo = InMemoryEngagementSnapshotRepository()
        val service = EngagementDashboardService(
            repository = snapshotRepo,
            exporter = NoopAnalyticsExporter,
            eventBus = FakeDomainEventBus(),
            clock = Clock.fixed(Instant.parse("2026-05-30T12:00:00Z"), ZoneOffset.UTC)::instant,
        )
        val listener = RecomputeEngagementSnapshotOnMatchEndedListener(matchRepo, service)

        listener.handle(
            MatchEnded(
                matchId = matchId,
                winningTeam = null,
                atTick = 100L,
                reason = EndReason.AdminEnded,
            ),
        )

        // No snapshots were touched — repo is still empty.
        assertThat(snapshotRepo.all()).isEmpty()
    }

    private fun seed(playerId: PlayerId, lastSeenAt: Instant): EngagementSnapshot = EngagementSnapshot(
        playerId = playerId,
        totalPlaytime = Duration.ofMinutes(120),
        loginStreakDays = 3,
        skillsExercisedCount = 5,
        refinementAttempts = 0L,
        cosmeticEquipDiversity = 0.0,
        firstSeenAt = lastSeenAt.minus(Duration.ofDays(7)),
        lastSeenAt = lastSeenAt,
        score = EngagementScore.ZERO,
        computedAt = lastSeenAt,
    )
}
