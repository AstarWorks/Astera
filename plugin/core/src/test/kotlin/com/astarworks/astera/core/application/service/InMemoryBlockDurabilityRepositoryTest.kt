package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockState
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Pins the in-memory [InMemoryBlockDurabilityRepository] (Wave 5x, ADR-0090).
 *
 * The Postgres overlay's IT lives in `plugin:persistence` and validates the
 * full cross-restart path against Testcontainers. This suite covers
 * port-shape semantics: load → upsert → delete, per-world isolation,
 * snapshot independence.
 */
internal class InMemoryBlockDurabilityRepositoryTest {

    private val world = WorldId("test-world")
    private val otherWorld = WorldId("other-world")
    private val pos = Vec3(10.0, 64.0, 20.0)
    private val pos2 = Vec3(11.0, 64.0, 20.0)
    private val attacker = PlayerId(UUID.randomUUID())

    private lateinit var repo: InMemoryBlockDurabilityRepository

    @BeforeEach
    fun setUp() {
        repo = InMemoryBlockDurabilityRepository()
    }

    private fun sampleState(durability: Int = 5000, maxHp: Int = 5000) = BlockState(
        blockType = BlockType("astera:hardened_soil"),
        durability = durability,
        lastDamagedTick = 0L,
        damagedBy = attacker,
        maxHp = maxHp,
    )

    @Test
    fun `loadAll returns empty for unseen world`() {
        val result = repo.loadAll(world)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value).isEmpty()
    }

    @Test
    fun `upsertAll then loadAll round-trips state`() {
        val state = sampleState()
        val upsertResult = repo.upsertAll(world, mapOf(pos to state))
        assertThat(upsertResult).isInstanceOf(Result.Success::class.java)

        val loaded = (repo.loadAll(world) as Result.Success).value
        assertThat(loaded).hasSize(1).containsKey(pos)
        assertThat(loaded[pos]).isEqualTo(state)
    }

    @Test
    fun `upsertAll merges with existing — does not delete missing positions`() {
        repo.upsertAll(world, mapOf(pos to sampleState(durability = 100)))
        // Second upsert only contains pos2; pos should NOT be deleted
        repo.upsertAll(world, mapOf(pos2 to sampleState(durability = 200)))

        val loaded = (repo.loadAll(world) as Result.Success).value
        assertThat(loaded).hasSize(2).containsKeys(pos, pos2)
    }

    @Test
    fun `upsertAll on existing position overwrites state`() {
        repo.upsertAll(world, mapOf(pos to sampleState(durability = 100)))
        repo.upsertAll(world, mapOf(pos to sampleState(durability = 50)))

        val loaded = (repo.loadAll(world) as Result.Success).value
        assertThat(loaded[pos]?.durability).isEqualTo(50)
    }

    @Test
    fun `loadAll is per-world scoped`() {
        repo.upsertAll(world, mapOf(pos to sampleState()))
        repo.upsertAll(otherWorld, mapOf(pos2 to sampleState()))

        assertThat((repo.loadAll(world) as Result.Success).value).hasSize(1).containsKey(pos)
        assertThat((repo.loadAll(otherWorld) as Result.Success).value).hasSize(1).containsKey(pos2)
    }

    @Test
    fun `deletePosition removes a single tracked entry`() {
        repo.upsertAll(world, mapOf(pos to sampleState(), pos2 to sampleState()))
        repo.deletePosition(world, pos)

        val loaded = (repo.loadAll(world) as Result.Success).value
        assertThat(loaded).hasSize(1).containsKey(pos2).doesNotContainKey(pos)
    }

    @Test
    fun `deletePositions bulk removes many entries`() {
        repo.upsertAll(world, mapOf(pos to sampleState(), pos2 to sampleState()))
        repo.deletePositions(world, listOf(pos, pos2))

        assertThat((repo.loadAll(world) as Result.Success).value).isEmpty()
    }

    @Test
    fun `delete on untracked position is a no-op success`() {
        val result = repo.deletePosition(world, pos)
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `upsertAll preserves maxHp for high-tier custom blocks`() {
        val high = sampleState(durability = 4500, maxHp = 5000)
        repo.upsertAll(world, mapOf(pos to high))

        val loaded = (repo.loadAll(world) as Result.Success).value[pos]
        assertThat(loaded?.maxHp).isEqualTo(5000)
        assertThat(loaded?.durability).isEqualTo(4500)
    }
}
