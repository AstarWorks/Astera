package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.items.domain.refinement.ItemInstance
import com.astarworks.astera.items.domain.refinement.ItemInstanceId
import com.astarworks.astera.items.domain.refinement.Refinement
import com.astarworks.astera.items.domain.refinement.SubStatKey
import com.astarworks.astera.items.domain.refinement.SubStatRoll
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Round-trip IT for [PostgresItemInstanceRepository] (Wave 5refine /
 * ADR-0100) against a real Postgres via Testcontainers. Verifies V30
 * applies and that save / findById / findByOwner preserve the
 * [ItemInstance] aggregate including the polymorphic [Refinement] layers
 * and the [SubStatRoll] list.
 */
public class PostgresItemInstanceRepositoryIT : PostgresIntegrationTestBase() {

    @Test
    public fun `save + findById round-trips a fresh instance with no layers`() {
        kotlinx.coroutines.runBlocking {
        val repo = PostgresItemInstanceRepository(db)
        val instance = ItemInstance(
            id = ItemInstanceId(UUID.randomUUID()),
            itemId = ItemId("example-sword"),
            owner = PlayerId(UUID.randomUUID()),
            createdAt = Instant.parse("2026-05-21T10:00:00Z"),
        )
        repo.save(instance)
        val found = repo.findById(instance.id)
        assertThat(found).isNotNull
        assertThat(found!!.itemId).isEqualTo(ItemId("example-sword"))
        assertThat(found.layers()).isEmpty()
        assertThat(found.subStatRolls()).isEmpty()
        assertThat(found.rerollCount()).isZero()
        }
    }

    @Test
    public fun `save + findById preserves every Refinement variant`() {
        kotlinx.coroutines.runBlocking {
        val repo = PostgresItemInstanceRepository(db)
        val owner = PlayerId(UUID.randomUUID())
        val instance = ItemInstance(
            id = ItemInstanceId(UUID.randomUUID()),
            itemId = ItemId("example-sword"),
            owner = owner,
            createdAt = Instant.parse("2026-05-21T10:00:00Z"),
            layers = listOf(
                Refinement.Reforge("sharp", 3),
                Refinement.Enchant("fire_aspect", 2),
                Refinement.Gemstone(0, "ruby", 4),
                Refinement.Star(7),
                Refinement.Recomb(15),
            ),
        )
        repo.save(instance)
        val found = repo.findById(instance.id)!!
        val layers = found.layers()
        assertThat(layers).hasSize(5)
        assertThat(layers[0]).isEqualTo(Refinement.Reforge("sharp", 3))
        assertThat(layers[1]).isEqualTo(Refinement.Enchant("fire_aspect", 2))
        assertThat(layers[2]).isEqualTo(Refinement.Gemstone(0, "ruby", 4))
        assertThat(layers[3]).isEqualTo(Refinement.Star(7))
        assertThat(layers[4]).isEqualTo(Refinement.Recomb(15))
        }
    }

    @Test
    public fun `save + findById preserves sub-stat rolls and reroll count`() {
        kotlinx.coroutines.runBlocking {
        val repo = PostgresItemInstanceRepository(db)
        val instance = ItemInstance(
            id = ItemInstanceId(UUID.randomUUID()),
            itemId = ItemId("example-sword"),
            owner = PlayerId(UUID.randomUUID()),
            createdAt = Instant.parse("2026-05-21T10:00:00Z"),
            subStatRolls = listOf(
                SubStatRoll(SubStatKey.ATTACK, 80.0, 2),
                SubStatRoll(SubStatKey.CRIT_CHANCE, 15.0, 1),
            ),
            rerollCount = 4,
        )
        repo.save(instance)
        val found = repo.findById(instance.id)!!
        val rolls = found.subStatRolls()
        assertThat(rolls).hasSize(2)
        assertThat(rolls.first { it.key == SubStatKey.ATTACK }.magnitude).isEqualTo(80.0)
        assertThat(rolls.first { it.key == SubStatKey.ATTACK }.rollCount).isEqualTo(2)
        assertThat(found.rerollCount()).isEqualTo(4)
        }
    }

    @Test
    public fun `findByOwner filters to one player`() {
        kotlinx.coroutines.runBlocking {
        val repo = PostgresItemInstanceRepository(db)
        val owner = PlayerId(UUID.randomUUID())
        val otherOwner = PlayerId(UUID.randomUUID())
        val mineA = ItemInstance(
            id = ItemInstanceId(UUID.randomUUID()),
            itemId = ItemId("example-sword"),
            owner = owner,
            createdAt = Instant.parse("2026-05-21T10:00:00Z"),
        )
        val mineB = ItemInstance(
            id = ItemInstanceId(UUID.randomUUID()),
            itemId = ItemId("example-bow"),
            owner = owner,
            createdAt = Instant.parse("2026-05-21T10:00:00Z"),
        )
        val notMine = ItemInstance(
            id = ItemInstanceId(UUID.randomUUID()),
            itemId = ItemId("example-shield"),
            owner = otherOwner,
            createdAt = Instant.parse("2026-05-21T10:00:00Z"),
        )
        repo.save(mineA); repo.save(mineB); repo.save(notMine)
        val mine = repo.findByOwner(owner)
        assertThat(mine.map { it.id }).containsExactlyInAnyOrder(mineA.id, mineB.id)
        }
    }

    @Test
    public fun `save acts as upsert on the primary key`() {
        kotlinx.coroutines.runBlocking {
        val repo = PostgresItemInstanceRepository(db)
        val id = ItemInstanceId(UUID.randomUUID())
        val owner = PlayerId(UUID.randomUUID())
        val first = ItemInstance(
            id = id,
            itemId = ItemId("example-sword"),
            owner = owner,
            createdAt = Instant.parse("2026-05-21T10:00:00Z"),
            layers = listOf(Refinement.Star(1)),
        )
        repo.save(first)
        val second = ItemInstance(
            id = id,
            itemId = ItemId("example-sword"),
            owner = owner,
            createdAt = Instant.parse("2026-05-21T10:00:00Z"),
            layers = listOf(Refinement.Star(1), Refinement.Reforge("sharp", 2)),
        )
        repo.save(second)
        val found = repo.findById(id)!!
        assertThat(found.layers()).hasSize(2)
        }
}
}
