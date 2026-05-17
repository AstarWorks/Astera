package com.astarworks.astera.weapons.application.service

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.Loadout
import com.astarworks.astera.core.domain.player.LoadoutSlot
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryLoadoutRepositoryTest {

    private val playerA = PlayerId(UUID.randomUUID())
    private val playerB = PlayerId(UUID.randomUUID())

    @Test
    fun `find returns null when nothing has been saved`() {
        val repo = InMemoryLoadoutRepository()
        assertThat(repo.find(playerA)).isNull()
    }

    @Test
    fun `save then find returns the persisted Loadout`() {
        val repo = InMemoryLoadoutRepository()
        val loadout = Loadout(mapOf(LoadoutSlot.MAIN to ItemId("example-sword")))
        repo.save(playerA, loadout)
        assertThat(repo.find(playerA)).isEqualTo(loadout)
    }

    @Test
    fun `save replaces prior aggregate wholesale`() {
        val repo = InMemoryLoadoutRepository()
        repo.save(
            playerA,
            Loadout(
                mapOf(
                    LoadoutSlot.MAIN to ItemId("example-sword"),
                    LoadoutSlot.SUB to ItemId("example-gun"),
                ),
            ),
        )
        repo.save(playerA, Loadout(mapOf(LoadoutSlot.MAIN to ItemId("example-sword"))))
        val found = repo.find(playerA)
        assertThat(found).isNotNull
        assertThat(found!!.itemIn(LoadoutSlot.MAIN)).isEqualTo(ItemId("example-sword"))
        assertThat(found.itemIn(LoadoutSlot.SUB)).isNull()
    }

    @Test
    fun `all returns every saved player's loadout`() {
        val repo = InMemoryLoadoutRepository()
        repo.save(playerA, Loadout(mapOf(LoadoutSlot.MAIN to ItemId("example-sword"))))
        repo.save(playerB, Loadout(mapOf(LoadoutSlot.SUB to ItemId("example-gun"))))
        val map = repo.all().toMap()
        assertThat(map).containsKeys(playerA, playerB)
        assertThat(map[playerA]!!.itemIn(LoadoutSlot.MAIN)).isEqualTo(ItemId("example-sword"))
        assertThat(map[playerB]!!.itemIn(LoadoutSlot.SUB)).isEqualTo(ItemId("example-gun"))
    }

    @Test
    fun `clear drops every entry`() {
        val repo = InMemoryLoadoutRepository()
        repo.save(playerA, Loadout(mapOf(LoadoutSlot.MAIN to ItemId("example-sword"))))
        repo.clear()
        assertThat(repo.find(playerA)).isNull()
        assertThat(repo.all()).isEmpty()
    }
}
