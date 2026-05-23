package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.engagement.EngagementScore
import com.astarworks.astera.core.domain.engagement.EngagementSnapshot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Round-trip IT for [PostgresEngagementSnapshotRepository] (Wave 5engage,
 * ADR-0104) against real Postgres via Testcontainers. Skipped when Docker
 * is unavailable (see [PostgresIntegrationTestBase]).
 */
public class PostgresEngagementSnapshotRepositoryIT : PostgresIntegrationTestBase() {

    /**
     * Exercise the JSONB codec end-to-end: persist a snapshot, read it back,
     * assert equality including the derived score. Timestamps are truncated
     * to microsecond precision because Postgres TIMESTAMP holds 6 fractional
     * digits while `Instant.now()` carries 9 (rounding would otherwise lose
     * the round-trip equality).
     */
    @Test
    public fun `upsert + find round-trips a snapshot`() {
        val repo = PostgresEngagementSnapshotRepository(db)
        val pid = PlayerId(UUID.randomUUID())
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val snap = EngagementSnapshot(
            playerId = pid,
            totalPlaytime = Duration.ofMinutes(123),
            loginStreakDays = 4,
            skillsExercisedCount = 2,
            refinementAttempts = 7L,
            cosmeticEquipDiversity = 0.25,
            firstSeenAt = now.minus(Duration.ofDays(30)),
            lastSeenAt = now,
            score = EngagementScore(0.42),
            computedAt = now,
        )

        repo.upsert(snap)
        val loaded = repo.find(pid)
        assertThat(loaded).isNotNull
        assertThat(loaded!!.playerId).isEqualTo(pid)
        assertThat(loaded.totalPlaytime).isEqualTo(Duration.ofMinutes(123))
        assertThat(loaded.loginStreakDays).isEqualTo(4)
        assertThat(loaded.refinementAttempts).isEqualTo(7L)
        assertThat(loaded.score.value).isEqualTo(0.42)
        assertThat(loaded.lastSeenAt).isEqualTo(now)
    }

    @Test
    public fun `upsert replaces an existing row`() {
        val repo = PostgresEngagementSnapshotRepository(db)
        val pid = PlayerId(UUID.randomUUID())
        val t0 = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val initial = EngagementSnapshot(
            playerId = pid,
            totalPlaytime = Duration.ofMinutes(10),
            loginStreakDays = 1,
            skillsExercisedCount = 1,
            refinementAttempts = 0,
            cosmeticEquipDiversity = 0.0,
            firstSeenAt = t0.minus(Duration.ofDays(1)),
            lastSeenAt = t0,
            score = EngagementScore(0.1),
            computedAt = t0,
        )
        repo.upsert(initial)

        val t1 = t0.plus(Duration.ofMinutes(5))
        repo.upsert(initial.copy(score = EngagementScore(0.9), computedAt = t1, lastSeenAt = t1))

        val loaded = repo.find(pid)!!
        assertThat(loaded.score.value).isEqualTo(0.9)
        assertThat(loaded.computedAt).isEqualTo(t1)
    }

    @Test
    public fun `topByScore returns highest-score first`() {
        val repo = PostgresEngagementSnapshotRepository(db)
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        fun snap(scoreVal: Double) = EngagementSnapshot(
            playerId = PlayerId(UUID.randomUUID()),
            totalPlaytime = Duration.ofMinutes(10),
            loginStreakDays = 1,
            skillsExercisedCount = 1,
            refinementAttempts = 0,
            cosmeticEquipDiversity = 0.0,
            firstSeenAt = now.minus(Duration.ofDays(1)),
            lastSeenAt = now,
            score = EngagementScore(scoreVal),
            computedAt = now,
        )
        repo.upsert(snap(0.1))
        repo.upsert(snap(0.9))
        repo.upsert(snap(0.5))

        val top = repo.topByScore(2)
        assertThat(top).hasSize(2)
        assertThat(top.map { it.score.value }).containsExactly(0.9, 0.5)
    }
}
