package com.astarworks.astera.core.application.usecase.settings

import com.astarworks.astera.core.application.service.InMemoryPlayerSettingsRepository
import com.astarworks.astera.core.domain.player.ControlScheme
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class SetControlSchemeUseCaseTest {

    private fun playerId(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `set switches the control scheme on a fresh player`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()

        SetControlSchemeUseCase(repo).set(id, ControlScheme.Button)

        assertThat(repo.find(id)!!.controlScheme).isEqualTo(ControlScheme.Button)
    }

    @Test
    fun `set preserves other fields when toggling control scheme`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()
        repo.upsert(
            PlayerSettings(id, doubleJumpEnabled = false, quickCastSlots = mapOf(1 to "astera:fireball")),
        )

        SetControlSchemeUseCase(repo).set(id, ControlScheme.Button)

        val current = repo.find(id)!!
        assertThat(current.controlScheme).isEqualTo(ControlScheme.Button)
        assertThat(current.doubleJumpEnabled).isFalse
        assertThat(current.quickCastSlots).containsEntry(1, "astera:fireball")
    }

    @Test
    fun `set can switch back to the right-click default`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()
        repo.upsert(PlayerSettings(id, controlScheme = ControlScheme.Button))

        SetControlSchemeUseCase(repo).set(id, ControlScheme.RightClick)

        assertThat(repo.find(id)!!.controlScheme).isEqualTo(ControlScheme.RightClick)
    }
}
