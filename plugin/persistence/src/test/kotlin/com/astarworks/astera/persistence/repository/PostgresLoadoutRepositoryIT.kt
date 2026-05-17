package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.Loadout
import com.astarworks.astera.core.domain.player.LoadoutSlot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Round-trip IT for [PostgresLoadoutRepository] (Wave 4.5 / ADR-0027) against
 * a real Postgres via Testcontainers. Verifies the V5 migration applied and
 * that save/find/all preserve the [Loadout] aggregate's multi-slot shape.
 */
public class PostgresLoadoutRepositoryIT : PostgresIntegrationTestBase() {

    @Test
    public fun `save + find round-trips a Loadout across multiple slots`() {
        val repo = PostgresLoadoutRepository(db)
        val playerId = PlayerId(UUID.randomUUID())
        val loadout = Loadout(
            mapOf(
                LoadoutSlot.MAIN to ItemId("example-sword"),
                LoadoutSlot.SUB to ItemId("example-gun"),
            ),
        )
        repo.save(playerId, loadout)
        val found = repo.find(playerId)
        assertThat(found).isNotNull
        assertThat(found!!.itemIn(LoadoutSlot.MAIN)).isEqualTo(ItemId("example-sword"))
        assertThat(found.itemIn(LoadoutSlot.SUB)).isEqualTo(ItemId("example-gun"))
    }

    @Test
    public fun `find returns null when no rows exist for the player`() {
        val repo = PostgresLoadoutRepository(db)
        val playerId = PlayerId(UUID.randomUUID())
        assertThat(repo.find(playerId)).isNull()
    }

    @Test
    public fun `save with smaller slot set drops removed slots`() {
        val repo = PostgresLoadoutRepository(db)
        val playerId = PlayerId(UUID.randomUUID())
        val initial = Loadout(
            mapOf(
                LoadoutSlot.MAIN to ItemId("example-sword"),
                LoadoutSlot.SUB to ItemId("example-gun"),
            ),
        )
        repo.save(playerId, initial)
        // Replace with only MAIN. The atomic DELETE+INSERT in [save] must
        // drop the SUB row so the persisted aggregate matches the new one.
        repo.save(playerId, Loadout(mapOf(LoadoutSlot.MAIN to ItemId("example-sword"))))
        val found = repo.find(playerId)
        assertThat(found).isNotNull
        assertThat(found!!.itemIn(LoadoutSlot.MAIN)).isEqualTo(ItemId("example-sword"))
        assertThat(found.itemIn(LoadoutSlot.SUB)).isNull()
    }

    @Test
    public fun `save with empty Loadout removes all rows for the player`() {
        val repo = PostgresLoadoutRepository(db)
        val playerId = PlayerId(UUID.randomUUID())
        repo.save(
            playerId,
            Loadout(mapOf(LoadoutSlot.MAIN to ItemId("example-sword"))),
        )
        repo.save(playerId, Loadout.EMPTY)
        assertThat(repo.find(playerId)).isNull()
    }

    @Test
    public fun `all returns every persisted player's loadout`() {
        val repo = PostgresLoadoutRepository(db)
        val p1 = PlayerId(UUID.randomUUID())
        val p2 = PlayerId(UUID.randomUUID())
        repo.save(p1, Loadout(mapOf(LoadoutSlot.MAIN to ItemId("example-sword"))))
        repo.save(p2, Loadout(mapOf(LoadoutSlot.SUB to ItemId("example-gun"))))
        val all = repo.all()
        val byPlayer = all.toMap()
        assertThat(byPlayer).containsKeys(p1, p2)
        assertThat(byPlayer[p1]!!.itemIn(LoadoutSlot.MAIN)).isEqualTo(ItemId("example-sword"))
        assertThat(byPlayer[p2]!!.itemIn(LoadoutSlot.SUB)).isEqualTo(ItemId("example-gun"))
    }
}
