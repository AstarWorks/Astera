package com.astarworks.astera.core.application.usecase.vault

import com.astarworks.astera.core.application.service.InMemoryPersonalVaultRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.vault.PersonalVault
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class OpenVaultUseCaseTest {

    private fun playerId(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `openVault returns the persisted vault when one exists`() {
        val repo = InMemoryPersonalVaultRepository()
        val id = playerId()
        val stored = PersonalVault(id, listOf(PersonalVault.VaultItem("astera:sword", 1)))
        repo.upsert(stored)

        val result = OpenVaultUseCase(repo).openVault(id)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value).isEqualTo(stored)
    }

    @Test
    fun `openVault returns an empty vault when no row exists`() {
        val repo = InMemoryPersonalVaultRepository()
        val id = playerId()

        val result = OpenVaultUseCase(repo).openVault(id)

        val opened = (result as Result.Success).value
        assertThat(opened.playerId).isEqualTo(id)
        assertThat(opened.items).isEmpty()
    }
}
