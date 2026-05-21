package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockState
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Round-trip IT for [PostgresBlockDurabilityRepository] (Wave 5x, ADR-0090)
 * against real Postgres via Testcontainers.
 *
 * Verifies:
 *  - V20 migration applies (`block_durability` table created with composite PK)
 *  - upsertAll → loadAll round-trips per-block maxHp and damagedBy
 *  - delete / deletePositions remove tracked entries
 *  - per-world isolation (same coordinates in two worlds keep independent state)
 *
 * Skipped automatically if Docker is unavailable (per
 * `PostgresIntegrationTestBase`).
 */
public class PostgresBlockDurabilityRepositoryIT : PostgresIntegrationTestBase() {

    private val worldA = WorldId("it-world-a")
    private val worldB = WorldId("it-world-b")
    private val pos = Vec3(10.0, 64.0, 20.0)
    private val pos2 = Vec3(11.0, 64.0, 20.0)
    private val player = PlayerId(UUID.fromString("11111111-1111-1111-1111-111111111111"))

    private fun sample(durability: Int = 5000, maxHp: Int = 5000) = BlockState(
        blockType = BlockType("astera:hardened_soil"),
        durability = durability,
        lastDamagedTick = 1234L,
        damagedBy = player,
        maxHp = maxHp,
    )

    @Test
    public fun `upsertAll then loadAll round-trips per-block maxHp`() {
        val repo = PostgresBlockDurabilityRepository(db)
        val upserted = sample(durability = 4500, maxHp = 5000)
        val r = repo.upsertAll(worldA, mapOf(pos to upserted))
        assertThat(r).isInstanceOf(Result.Success::class.java)

        val loaded = (repo.loadAll(worldA) as Result.Success).value
        assertThat(loaded).hasSize(1).containsKey(pos)
        val state = loaded[pos]!!
        assertThat(state.durability).isEqualTo(4500)
        assertThat(state.maxHp).isEqualTo(5000)
        assertThat(state.damagedBy).isEqualTo(player)
        assertThat(state.lastDamagedTick).isEqualTo(1234L)
        assertThat(state.blockType).isEqualTo(BlockType("astera:hardened_soil"))
    }

    @Test
    public fun `upsertAll twice on same position overwrites state`() {
        val repo = PostgresBlockDurabilityRepository(db)
        repo.upsertAll(worldA, mapOf(pos to sample(durability = 5000, maxHp = 5000)))
        repo.upsertAll(worldA, mapOf(pos to sample(durability = 100, maxHp = 5000)))

        val state = (repo.loadAll(worldA) as Result.Success).value[pos]!!
        assertThat(state.durability).isEqualTo(100)
    }

    @Test
    public fun `loadAll is per-world scoped`() {
        val repo = PostgresBlockDurabilityRepository(db)
        repo.upsertAll(worldA, mapOf(pos to sample()))
        repo.upsertAll(worldB, mapOf(pos2 to sample()))

        val a = (repo.loadAll(worldA) as Result.Success).value
        val b = (repo.loadAll(worldB) as Result.Success).value
        assertThat(a).hasSize(1).containsKey(pos)
        assertThat(b).hasSize(1).containsKey(pos2)
    }

    @Test
    public fun `deletePosition removes only the targeted entry`() {
        val repo = PostgresBlockDurabilityRepository(db)
        repo.upsertAll(worldA, mapOf(pos to sample(), pos2 to sample()))
        repo.deletePosition(worldA, pos)

        val loaded = (repo.loadAll(worldA) as Result.Success).value
        assertThat(loaded).hasSize(1).containsKey(pos2).doesNotContainKey(pos)
    }

    @Test
    public fun `deletePositions bulk removes many entries`() {
        val repo = PostgresBlockDurabilityRepository(db)
        repo.upsertAll(worldA, mapOf(pos to sample(), pos2 to sample()))
        repo.deletePositions(worldA, listOf(pos, pos2))
        assertThat((repo.loadAll(worldA) as Result.Success).value).isEmpty()
    }

    @Test
    public fun `state with null damagedBy round-trips`() {
        val repo = PostgresBlockDurabilityRepository(db)
        val state = BlockState(
            blockType = BlockType("astera:hardened_soil"),
            durability = 100,
            lastDamagedTick = 0L,
            damagedBy = null,
            maxHp = 100,
        )
        repo.upsertAll(worldA, mapOf(pos to state))
        val loaded = (repo.loadAll(worldA) as Result.Success).value[pos]!!
        assertThat(loaded.damagedBy).isNull()
    }
}
