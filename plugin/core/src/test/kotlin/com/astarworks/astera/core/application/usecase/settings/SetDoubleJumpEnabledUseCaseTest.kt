package com.astarworks.astera.core.application.usecase.settings

import com.astarworks.astera.core.application.service.InMemoryPlayerSettingsRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class SetDoubleJumpEnabledUseCaseTest {

    private fun playerId(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `set disables double jump on a fresh player`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()

        SetDoubleJumpEnabledUseCase(repo).set(id, enabled = false)

        assertThat(repo.find(id)!!.doubleJumpEnabled).isFalse
    }

    @Test
    fun `set re-enables double jump after a previous disable`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()
        repo.upsert(PlayerSettings(id, doubleJumpEnabled = false))

        SetDoubleJumpEnabledUseCase(repo).set(id, enabled = true)

        assertThat(repo.find(id)!!.doubleJumpEnabled).isTrue
    }
}
