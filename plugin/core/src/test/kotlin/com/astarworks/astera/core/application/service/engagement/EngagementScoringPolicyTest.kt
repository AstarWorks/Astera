package com.astarworks.astera.core.application.service.engagement

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class EngagementScoringPolicyTest {

    private val now = Instant.parse("2026-05-21T00:00:00Z")

    @Test
    fun `idle never-seen player scores zero`() {
        val score = EngagementScoringPolicy.score(
            totalPlaytime = Duration.ZERO,
            loginStreakDays = 0,
            skillsExercisedCount = 0,
            lastSeenAt = now.minus(Duration.ofDays(30)),
            now = now,
        )
        assertThat(score.value).isEqualTo(0.0)
    }

    @Test
    fun `fully-engaged player saturates to one`() {
        val score = EngagementScoringPolicy.score(
            totalPlaytime = Duration.ofHours(200),
            loginStreakDays = 60,
            skillsExercisedCount = 20,
            lastSeenAt = now,
            now = now,
        )
        assertThat(score.value).isEqualTo(1.0)
    }

    @Test
    fun `recency decays linearly to zero over fourteen days`() {
        val justNow = EngagementScoringPolicy.score(
            totalPlaytime = Duration.ZERO,
            loginStreakDays = 0,
            skillsExercisedCount = 0,
            lastSeenAt = now,
            now = now,
        )
        val sevenDays = EngagementScoringPolicy.score(
            totalPlaytime = Duration.ZERO,
            loginStreakDays = 0,
            skillsExercisedCount = 0,
            lastSeenAt = now.minus(Duration.ofDays(7)),
            now = now,
        )
        val fourteenDays = EngagementScoringPolicy.score(
            totalPlaytime = Duration.ZERO,
            loginStreakDays = 0,
            skillsExercisedCount = 0,
            lastSeenAt = now.minus(Duration.ofDays(14)),
            now = now,
        )
        // With all other components zero, recency drives the score:
        //   day 0  -> recencyComponent = 1.0 * 0.2 = 0.20
        //   day 7  -> recencyComponent = 0.5 * 0.2 = 0.10
        //   day 14 -> recencyComponent = 0.0
        assertThat(justNow.value).isEqualTo(0.20, within(0.001))
        assertThat(sevenDays.value).isEqualTo(0.10, within(0.001))
        assertThat(fourteenDays.value).isEqualTo(0.0)
    }

    @Test
    fun `mid-tier player produces sane weighted value`() {
        // 50h playtime (=0.5 of cap), 15d streak (=0.5), 5 skills (=0.5), seen now (=1.0).
        // Score = 0.5*0.35 + 0.5*0.25 + 0.5*0.20 + 1.0*0.20 = 0.175 + 0.125 + 0.10 + 0.20 = 0.6
        val score = EngagementScoringPolicy.score(
            totalPlaytime = Duration.ofHours(50),
            loginStreakDays = 15,
            skillsExercisedCount = 5,
            lastSeenAt = now,
            now = now,
        )
        assertThat(score.value).isEqualTo(0.6, within(0.001))
    }

    @Test
    fun `future lastSeen is clamped to recency 1`() {
        // Clock skew defensive: a snapshot with lastSeen > now should be treated
        // as "just seen" rather than producing a nonsensical negative recency.
        val score = EngagementScoringPolicy.score(
            totalPlaytime = Duration.ZERO,
            loginStreakDays = 0,
            skillsExercisedCount = 0,
            lastSeenAt = now.plus(Duration.ofMinutes(5)),
            now = now,
        )
        assertThat(score.value).isEqualTo(0.20, within(0.001))
    }
}
