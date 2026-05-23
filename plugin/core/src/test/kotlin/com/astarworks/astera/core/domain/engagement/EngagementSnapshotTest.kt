package com.astarworks.astera.core.domain.engagement

import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class EngagementSnapshotTest {

    private val pid = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val firstSeen = Instant.parse("2026-05-01T00:00:00Z")
    private val lastSeen = Instant.parse("2026-05-20T12:00:00Z")
    private val now = Instant.parse("2026-05-21T00:00:00Z")

    private fun snapshot(
        playtime: Duration = Duration.ofHours(50),
        streak: Int = 5,
        skills: Int = 3,
        firstSeenAt: Instant = firstSeen,
        lastSeenAt: Instant = lastSeen,
    ) = EngagementSnapshot(
        playerId = pid,
        totalPlaytime = playtime,
        loginStreakDays = streak,
        skillsExercisedCount = skills,
        refinementAttempts = 0L,
        cosmeticEquipDiversity = 0.0,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
        score = EngagementScore(0.5),
        computedAt = now,
    )

    @Test
    fun `valid snapshot constructs`() {
        val s = snapshot()
        assertThat(s.playerId).isEqualTo(pid)
        assertThat(s.score.value).isEqualTo(0.5)
    }

    @Test
    fun `firstSeen must not be after lastSeen`() {
        assertThatThrownBy {
            snapshot(firstSeenAt = lastSeen, lastSeenAt = firstSeen)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `negative inputs rejected`() {
        assertThatThrownBy { snapshot(streak = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { snapshot(skills = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { snapshot(playtime = Duration.ofSeconds(-1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `EngagementDashboardSnapshot enforces sorted-desc top list`() {
        val top1 = snapshot().copy(score = EngagementScore(0.9))
        val top2 = snapshot().copy(score = EngagementScore(0.3))
        val ok = EngagementDashboardSnapshot(
            dauWauMau = DauWauMau.EMPTY,
            retentionByCohort = emptyMap(),
            topEngaged = listOf(top1, top2),
            computedAt = now,
        )
        assertThat(ok.topEngaged.map { it.score.value }).containsExactly(0.9, 0.3)

        assertThatThrownBy {
            EngagementDashboardSnapshot(
                dauWauMau = DauWauMau.EMPTY,
                retentionByCohort = emptyMap(),
                topEngaged = listOf(top2, top1),
                computedAt = now,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `EngagementDashboardSnapshot empty factory yields empty maps`() {
        val empty = EngagementDashboardSnapshot.empty()
        assertThat(empty.dauWauMau).isEqualTo(DauWauMau.EMPTY)
        assertThat(empty.retentionByCohort).isEmpty()
        assertThat(empty.topEngaged).isEmpty()
    }
}
