package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** Round-trip IT for [PostgresSkillSkinRepository] (V74): ownership, one selected skin per skill, survival across a fresh instance. */
public class PostgresSkillSkinRepositoryIT : PostgresIntegrationTestBase() {

    @Test
    public fun `grant select revoke persist across a fresh repository`() {
        val player = PlayerId(UUID.randomUUID())
        val repo = PostgresSkillSkinRepository(db)

        assertThat(repo.selected(player, "couture-rapier-s2")).isNull()
        assertThat(repo.select(player, "couture-rapier-s2", "crimson")).isFalse()

        repo.grant(player, "couture-rapier-s2", "crimson")
        repo.grant(player, "couture-rapier-s2", "azure")
        repo.grant(player, "couture-rapier-s2", "azure")
        assertThat(repo.select(player, "couture-rapier-s2", "crimson")).isTrue()
        assertThat(repo.select(player, "couture-rapier-s2", "azure")).isTrue()

        val fresh = PostgresSkillSkinRepository(db)
        assertThat(fresh.owned(player, "couture-rapier-s2")).containsExactlyInAnyOrder("crimson", "azure")
        assertThat(fresh.selected(player, "couture-rapier-s2")).isEqualTo("azure")

        fresh.revoke(player, "couture-rapier-s2", "azure")
        assertThat(PostgresSkillSkinRepository(db).selected(player, "couture-rapier-s2")).isNull()
        assertThat(fresh.select(player, "couture-rapier-s2", null)).isTrue()
        assertThat(PostgresSkillSkinRepository(db).owned(player, "couture-rapier-s2")).containsExactly("crimson")
    }
}
