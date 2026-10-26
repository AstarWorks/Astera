package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.progression.SkillKind
import com.astarworks.astera.core.domain.progression.SkillProgression
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5multi-skill (ADR-0101 / V30) — round-trip + composite-key + bulk
 * upsert for the multi-axis skill progression table.
 */
class PostgresSkillProgressionRepositoryIT : PostgresIntegrationTestBase() {

    @Test
    fun `upsert + find round-trip per axis`() {
        kotlinx.coroutines.runBlocking {
        val repo = PostgresSkillProgressionRepository(db)
        val id = PlayerId(UUID.randomUUID())
        val combat = SkillProgression(id, SkillKind.Combat, xp = 1000L, level = 5)
        repo.upsert(combat)

        val found = repo.find(id, SkillKind.Combat)
        assertThat(found).isEqualTo(combat)
        assertThat(repo.find(id, SkillKind.Mining)).isNull()
        }
    }

    @Test
    fun `upsert overwrites existing row`() {
        kotlinx.coroutines.runBlocking {
        val repo = PostgresSkillProgressionRepository(db)
        val id = PlayerId(UUID.randomUUID())
        repo.upsert(SkillProgression(id, SkillKind.Mining, xp = 100L, level = 2))
        repo.upsert(SkillProgression(id, SkillKind.Mining, xp = 250L, level = 3))

        val found = repo.find(id, SkillKind.Mining)
        assertThat(found?.xp).isEqualTo(250L)
        assertThat(found?.level).isEqualTo(3)
        }
    }

    @Test
    fun `allFor returns every axis for a player`() {
        kotlinx.coroutines.runBlocking {
        val repo = PostgresSkillProgressionRepository(db)
        val id = PlayerId(UUID.randomUUID())
        repo.upsertAll(
            listOf(
                SkillProgression(id, SkillKind.Combat, xp = 100L, level = 2),
                SkillProgression(id, SkillKind.Mining, xp = 200L, level = 3),
                SkillProgression(id, SkillKind.Trading, xp = 50L, level = 1),
            ),
        )

        val all = repo.allFor(id)
        assertThat(all).hasSize(3)
        assertThat(all.map { it.kind }).containsExactlyInAnyOrder(
            SkillKind.Combat,
            SkillKind.Mining,
            SkillKind.Trading,
        )
        }
    }

    @Test
    fun `allFor scopes to one player`() {
        kotlinx.coroutines.runBlocking {
        val repo = PostgresSkillProgressionRepository(db)
        val a = PlayerId(UUID.randomUUID())
        val b = PlayerId(UUID.randomUUID())
        repo.upsert(SkillProgression(a, SkillKind.Combat, xp = 100L, level = 2))
        repo.upsert(SkillProgression(b, SkillKind.Combat, xp = 999L, level = 4))

        assertThat(repo.allFor(a)).hasSize(1)
        assertThat(repo.allFor(a).first().xp).isEqualTo(100L)
        assertThat(repo.allFor(b).first().xp).isEqualTo(999L)
        }
}
}
