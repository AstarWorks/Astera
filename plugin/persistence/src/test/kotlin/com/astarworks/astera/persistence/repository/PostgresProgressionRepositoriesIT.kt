package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.core.domain.loginbonus.LoginStreakRecord
import com.astarworks.astera.core.domain.norma.MissionProgress
import com.astarworks.astera.core.domain.norma.NormaId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import com.astarworks.astera.stats.domain.achievement.PlayerAchievement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** ADR-0210: V60 achievements, V61 login streaks and the V62 norma period key against real Postgres. */
public class PostgresProgressionRepositoriesIT : PostgresIntegrationTestBase() {

    @Test
    public fun `achievement unlocks survive a new repository and a repeat unlock is a no-op`() {
        kotlinx.coroutines.runBlocking {
        val id = PlayerId(UUID.randomUUID())
        val kills = AchievementId("kills-10")
        val repo = PostgresAchievementRepository(db)

        assertThat(repo.isUnlocked(id, kills)).isFalse
        repo.unlock(PlayerAchievement(id, kills, Instant.parse("2026-10-22T00:00:00Z")))
        repo.unlock(PlayerAchievement(id, kills, Instant.parse("2026-10-23T00:00:00Z")))

        val reborn = PostgresAchievementRepository(db)
        assertThat(reborn.isUnlocked(id, kills)).isTrue
        assertThat(reborn.unlocked(id)).containsExactly(kills)
        }
    }

    @Test
    public fun `login streak round trips and upsert replaces`() {
        kotlinx.coroutines.runBlocking {
        val id = PlayerId(UUID.randomUUID())
        val repo = PostgresLoginStreakRepository(db)

        assertThat(repo.findByPlayer(id)).isNull()
        repo.upsert(LoginStreakRecord(id, 1, LocalDate.of(2026, 10, 21)))
        repo.upsert(LoginStreakRecord(id, 2, LocalDate.of(2026, 10, 22)))

        assertThat(PostgresLoginStreakRepository(db).findByPlayer(id))
            .isEqualTo(LoginStreakRecord(id, 2, LocalDate.of(2026, 10, 22)))
        }
    }

    @Test
    public fun `norma progress keeps its period key`() {
        kotlinx.coroutines.runBlocking {
        val id = PlayerId(UUID.randomUUID())
        val repo = PostgresNormaProgressRepository(db)
        val row = MissionProgress(id, NormaId("daily-killer-1"), 3L, false, null, periodKey = "2026-10-22")

        repo.upsert(id, row)

        assertThat(PostgresNormaProgressRepository(db).find(id, NormaId("daily-killer-1"))).isEqualTo(row)
        }
}
}
