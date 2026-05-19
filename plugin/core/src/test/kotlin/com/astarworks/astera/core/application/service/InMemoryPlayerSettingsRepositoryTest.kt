package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.player.ControlScheme
import com.astarworks.astera.core.domain.player.ParticleVisibility
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryPlayerSettingsRepositoryTest {

    private fun playerId(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `upsert then find returns the same settings`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()
        val settings = PlayerSettings(
            playerId = id,
            controlScheme = ControlScheme.Button,
            particleVisibility = ParticleVisibility.OwnTeamOnly,
            doubleJumpEnabled = false,
            quickCastSlots = mapOf(1 to "astera:fireball", 4 to "astera:dash"),
        )
        repo.upsert(settings)

        assertThat(repo.find(id)).isEqualTo(settings)
    }

    @Test
    fun `find returns null when no row exists`() {
        val repo = InMemoryPlayerSettingsRepository()
        assertThat(repo.find(playerId())).isNull()
    }

    @Test
    fun `findOrDefault returns PlayerSettings default when no row exists`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()

        val result = repo.findOrDefault(id)

        assertThat(result).isEqualTo(PlayerSettings.default(id))
        assertThat(result.controlScheme).isEqualTo(ControlScheme.RightClick)
        assertThat(result.particleVisibility).isEqualTo(ParticleVisibility.All)
        assertThat(result.doubleJumpEnabled).isTrue
        assertThat(result.quickCastSlots).isEmpty()
    }

    @Test
    fun `findOrDefault returns the stored row when present`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()
        val stored = PlayerSettings(id, controlScheme = ControlScheme.Button)
        repo.upsert(stored)

        assertThat(repo.findOrDefault(id)).isEqualTo(stored)
    }

    @Test
    fun `upsert overwrites the prior settings for the same player`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()
        repo.upsert(PlayerSettings(id, doubleJumpEnabled = true))
        repo.upsert(PlayerSettings(id, doubleJumpEnabled = false))

        assertThat(repo.find(id)!!.doubleJumpEnabled).isFalse
    }
}
