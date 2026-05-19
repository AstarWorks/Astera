package com.astarworks.astera.core.application.usecase.vault

import com.astarworks.astera.core.application.service.InMemoryPersonalVaultRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.vault.PersonalVault
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class SaveVaultUseCaseTest {

    private fun playerId(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `save persists a valid vault and returns Success`() {
        val repo = InMemoryPersonalVaultRepository()
        val id = playerId()
        val vault = PersonalVault(id, listOf(PersonalVault.VaultItem("astera:sword", 1)))

        val result = SaveVaultUseCase(repo).save(vault)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(repo.find(id)).isEqualTo(vault)
    }

    @Test
    fun `save with empty vault is allowed and persists an empty aggregate`() {
        val repo = InMemoryPersonalVaultRepository()
        val id = playerId()
        val vault = PersonalVault.empty(id)

        val result = SaveVaultUseCase(repo).save(vault)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(repo.find(id)!!.items).isEmpty()
    }

    @Test
    fun `the domain init rejects VaultItem with count zero at construction`() {
        // Construction-time guard means SaveVaultUseCase's own validation is a
        // safety-net, not the primary check. Documented here for the maintainer.
        assertThatThrownBy {
            PersonalVault(playerId(), listOf(PersonalVault.VaultItem("zero", 0)))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
