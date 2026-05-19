package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.vault.PersonalVault
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Round-trip IT for [PostgresPersonalVaultRepository] (Wave 5e / RTM
 * issue #3) against real Postgres via Testcontainers. Skipped (via
 * [org.junit.jupiter.api.Assumptions]) when Docker is unavailable.
 */
public class PostgresPersonalVaultRepositoryIT : PostgresIntegrationTestBase() {

    @Test
    public fun `upsert + find round-trips a vault including a VaultItem with NBT`() {
        val repo = PostgresPersonalVaultRepository(db)
        val id = PlayerId(UUID.randomUUID())
        val nbt = """{"Enchantments":[{"id":"minecraft:sharpness","lvl":5}]}"""
        val vault = PersonalVault(
            playerId = id,
            items = listOf(
                PersonalVault.VaultItem("astera:sword", count = 1, nbt = nbt),
                PersonalVault.VaultItem("astera:potion", count = 32),
                PersonalVault.VaultItem("astera:emerald", count = 64),
            ),
        )

        repo.upsert(vault)
        val found = repo.find(id)

        assertThat(found).isNotNull
        assertThat(found!!.items).hasSize(3)
        assertThat(found.items.map { it.itemId }).containsExactly(
            "astera:sword", "astera:potion", "astera:emerald",
        )
        assertThat(found.items[0].nbt).isEqualTo(nbt)
        assertThat(found.items[1].count).isEqualTo(32)
        assertThat(found.items[2].count).isEqualTo(64)
    }

    @Test
    public fun `find returns null when no row exists`() {
        val repo = PostgresPersonalVaultRepository(db)
        assertThat(repo.find(PlayerId(UUID.randomUUID()))).isNull()
    }

    @Test
    public fun `upsert replaces the prior items array atomically`() {
        val repo = PostgresPersonalVaultRepository(db)
        val id = PlayerId(UUID.randomUUID())
        repo.upsert(PersonalVault(id, listOf(PersonalVault.VaultItem("old", 1))))

        repo.upsert(
            PersonalVault(id, listOf(PersonalVault.VaultItem("new-a", 4), PersonalVault.VaultItem("new-b", 8))),
        )

        val found = repo.find(id)!!
        assertThat(found.items.map { it.itemId }).containsExactly("new-a", "new-b")
    }

    @Test
    public fun `remove deletes the row`() {
        val repo = PostgresPersonalVaultRepository(db)
        val id = PlayerId(UUID.randomUUID())
        repo.upsert(PersonalVault(id, listOf(PersonalVault.VaultItem("x", 1))))

        repo.remove(id)

        assertThat(repo.find(id)).isNull()
    }
}
