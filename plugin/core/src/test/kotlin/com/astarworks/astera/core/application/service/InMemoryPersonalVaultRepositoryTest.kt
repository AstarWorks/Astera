package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.vault.PersonalVault
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryPersonalVaultRepositoryTest {

    private fun playerId(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `upsert then find returns the same vault`() {
        val repo = InMemoryPersonalVaultRepository()
        val id = playerId()
        val vault = PersonalVault(
            playerId = id,
            items = listOf(
                PersonalVault.VaultItem(slot = 0, "astera:sword", count = 1),
                PersonalVault.VaultItem(slot = 1, "astera:potion", count = 32),
            ),
        )
        repo.upsert(vault)

        assertThat(repo.find(id)).isEqualTo(vault)
    }

    @Test
    fun `find returns null when player has no vault yet`() {
        val repo = InMemoryPersonalVaultRepository()

        assertThat(repo.find(playerId())).isNull()
    }

    @Test
    fun `upsert preserves item order across the round trip`() {
        val repo = InMemoryPersonalVaultRepository()
        val id = playerId()
        val items = listOf(
            PersonalVault.VaultItem(slot = 2, "a", 1),
            PersonalVault.VaultItem(slot = 3, "b", 2),
            PersonalVault.VaultItem(slot = 4, "c", 3),
        )
        repo.upsert(PersonalVault(id, items))

        assertThat(repo.find(id)!!.items.map { it.itemId }).containsExactly("a", "b", "c")
    }

    @Test
    fun `upsert overwrites the prior vault for the same player`() {
        val repo = InMemoryPersonalVaultRepository()
        val id = playerId()
        repo.upsert(PersonalVault(id, listOf(PersonalVault.VaultItem(slot = 5, "old", 1))))
        repo.upsert(PersonalVault(id, listOf(PersonalVault.VaultItem(slot = 6, "new", 2))))

        val current = repo.find(id)!!
        assertThat(current.items).hasSize(1)
        assertThat(current.items[0].itemId).isEqualTo("new")
    }

    @Test
    fun `remove makes find return null`() {
        val repo = InMemoryPersonalVaultRepository()
        val id = playerId()
        repo.upsert(PersonalVault(id, listOf(PersonalVault.VaultItem(slot = 7, "x", 1))))

        repo.remove(id)

        assertThat(repo.find(id)).isNull()
    }

    @Test
    fun `remove on an unknown player is a no-op`() {
        val repo = InMemoryPersonalVaultRepository()
        repo.remove(playerId())
        // Just must not throw.
    }

    @Test
    fun `vault items with NBT round-trip through the repository`() {
        val repo = InMemoryPersonalVaultRepository()
        val id = playerId()
        val nbt = """{"Enchantments":[{"id":"minecraft:sharpness","lvl":5}]}"""
        repo.upsert(PersonalVault(id, listOf(PersonalVault.VaultItem(slot = 8, "astera:sword", 1, nbt))))

        assertThat(repo.find(id)!!.items[0].nbt).isEqualTo(nbt)
    }
}
