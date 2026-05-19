package com.astarworks.astera.core.application.usecase.settings

import com.astarworks.astera.core.application.service.InMemoryPlayerSettingsRepository
import com.astarworks.astera.core.domain.player.ControlScheme
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class GetPlayerSettingsUseCaseTest {

    private fun playerId(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `get returns the persisted settings when present`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()
        val stored = PlayerSettings(id, controlScheme = ControlScheme.Button)
        repo.upsert(stored)

        assertThat(GetPlayerSettingsUseCase(repo).get(id)).isEqualTo(stored)
    }

    @Test
    fun `get returns the domain default when no row exists`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()

        val result = GetPlayerSettingsUseCase(repo).get(id)

        assertThat(result).isEqualTo(PlayerSettings.default(id))
        assertThat(result.doubleJumpEnabled).isTrue
    }
}
