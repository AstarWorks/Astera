package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stats.PlayerStats
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5e (ADR-0058 §5) rewrite: [PostgresPlayerStatsRepository] is a thin
 * facade over [PostgresPlayerAttributeContainer]. Round-trip exercises the
 * V14 JSONB row + the container-store contract.
 */
public class PostgresPlayerStatsRepositoryIT : PostgresIntegrationTestBase() {

    @Test
    public fun `first stat mutation and detached upserts survive a new store`() {
        val id = PlayerId(UUID.randomUUID())
        val store = attributeStore().load(id)
        val repo = PostgresPlayerStatsRepository(store)
        val first = repo.find(id) ?: PlayerStats.empty(id)
        first.container.set(
            com.astarworks.astera.core.domain.attribute.Attributes.STAT_KILLS,
            com.astarworks.astera.core.domain.attribute.AttributeValue.NumberValue(1.0),
        )
        repo.upsert(first)
        val reloaded = PostgresPlayerStatsRepository(attributeStore().load(id))
        assertThat(reloaded.find(id)?.kills).isEqualTo(1L)

        repo.upsert(PlayerStats.of(id, kills = 3L, deaths = 2L))
        val afterUpsert = PostgresPlayerStatsRepository(attributeStore().load(id)).find(id)!!
        assertThat(afterUpsert.kills).isEqualTo(3L)
        assertThat(afterUpsert.deaths).isEqualTo(2L)
    }

    @Test
    public fun `upsert + find round-trips a PlayerStats`() {
        val id = PlayerId(UUID.randomUUID())
        val containerStore = attributeStore().load(id)
        val repo = PostgresPlayerStatsRepository(containerStore)
        val stats = PlayerStats.of(
            playerId = id,
            kills = 7L,
            deaths = 3L,
            assists = 2L,
            damageDealt = 1500L,
            damageTaken = 900L,
            playtimeSec = 1200L,
            matchesPlayed = 4L,
            wins = 2L,
            losses = 2L,
        )
        // Wave 5e: hydrate the Postgres container with the seed values, then
        // flush via upsert. find() reads through the V14 JSONB row.
        val target = containerStore.containerFor(id)
        stats.container.keys().forEach { key ->
            target.set(key, stats.container.get(key)!!)
        }
        repo.upsert(PlayerStats(id, target))

        val found = repo.find(id)
        assertThat(found).isNotNull
        assertThat(found!!.kills).isEqualTo(7L)
        assertThat(found.wins).isEqualTo(2L)
        assertThat(found.deaths).isEqualTo(3L)
    }
}
