package com.astarworks.astera.core.application.usecase.vault

import com.astarworks.astera.core.application.service.InMemoryPersonalVaultRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.vault.PersonalVault
import com.astarworks.astera.core.domain.vault.PersonalVault.VaultItem
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/** ADR-0211: the vault is MAX_PAGES pages of PAGE_SIZE slots; a page save never touches another page. */
class VaultPagesTest {

    private val id = PlayerId(UUID.randomUUID())

    @Test
    fun `capacity is pages times page size and the old 27 slots stay on page 0`() {
        assertThat(PersonalVault.CAPACITY).isEqualTo(PersonalVault.PAGE_SIZE * PersonalVault.MAX_PAGES)
        val legacy = PersonalVault(id, listOf(VaultItem(26, "minecraft:stick", 3)))
        assertThat(legacy.pageItems(0).single().slot).isEqualTo(26)
        assertThat(legacy.pageItems(1)).isEmpty()
    }

    @Test
    fun `pageItems maps global slots to window-local slots`() {
        val vault = PersonalVault(id, listOf(VaultItem(PersonalVault.PAGE_SIZE + 4, "minecraft:stick", 1)))
        assertThat(vault.pageItems(1).single().slot).isEqualTo(4)
    }

    @Test
    fun `withPage replaces only that page`() {
        val vault = PersonalVault(id, listOf(VaultItem(0, "a", 1), VaultItem(PersonalVault.PAGE_SIZE, "b", 2)))
        val next = vault.withPage(1, listOf(VaultItem(7, "c", 5)))
        assertThat(next.itemAt(0)?.itemId).isEqualTo("a")
        assertThat(next.itemAt(PersonalVault.PAGE_SIZE)).isNull()
        assertThat(next.itemAt(PersonalVault.PAGE_SIZE + 7)?.itemId).isEqualTo("c")
    }

    @Test
    fun `an item beyond the last page is rejected`() {
        assertThatThrownBy { VaultItem(PersonalVault.CAPACITY, "x", 1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { PersonalVault.empty(id).pageItems(PersonalVault.MAX_PAGES) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `savePage keeps the other pages and an emptied page is cleared`() {
        val repo = InMemoryPersonalVaultRepository()
        val save = SaveVaultUseCase(repo)
        assertThat(save.savePage(id, 0, listOf(VaultItem(1, "a", 1)))).isInstanceOf(Result.Success::class.java)
        assertThat(save.savePage(id, 2, listOf(VaultItem(2, "z", 9)))).isInstanceOf(Result.Success::class.java)

        save.savePage(id, 0, emptyList())

        val stored = repo.find(id)!!
        assertThat(stored.items.map { it.itemId }).containsExactly("z")
        assertThat(stored.itemAt(2 * PersonalVault.PAGE_SIZE + 2)?.count).isEqualTo(9)
    }

    @Test
    fun `savePage rejects a page or slot outside the vault`() {
        val save = SaveVaultUseCase(InMemoryPersonalVaultRepository())
        assertThat(save.savePage(id, PersonalVault.MAX_PAGES, emptyList())).isInstanceOf(Result.Failure::class.java)
    }
}
