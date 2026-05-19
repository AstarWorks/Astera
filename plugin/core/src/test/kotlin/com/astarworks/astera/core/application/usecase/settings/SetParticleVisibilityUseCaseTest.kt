package com.astarworks.astera.core.application.usecase.settings

import com.astarworks.astera.core.application.service.InMemoryPlayerSettingsRepository
import com.astarworks.astera.core.domain.player.ParticleVisibility
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class SetParticleVisibilityUseCaseTest {

    private fun playerId(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `set persists the new visibility on a fresh player`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()

        SetParticleVisibilityUseCase(repo).set(id, ParticleVisibility.OwnOnly)

        assertThat(repo.find(id)!!.particleVisibility).isEqualTo(ParticleVisibility.OwnOnly)
    }

    @Test
    fun `set cycles through every visibility variant`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()
        val uc = SetParticleVisibilityUseCase(repo)

        uc.set(id, ParticleVisibility.OwnTeamOnly)
        assertThat(repo.find(id)!!.particleVisibility).isEqualTo(ParticleVisibility.OwnTeamOnly)

        uc.set(id, ParticleVisibility.OwnOnly)
        assertThat(repo.find(id)!!.particleVisibility).isEqualTo(ParticleVisibility.OwnOnly)

        uc.set(id, ParticleVisibility.All)
        assertThat(repo.find(id)!!.particleVisibility).isEqualTo(ParticleVisibility.All)
    }

    @Test
    fun `set preserves other typed fields`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()
        repo.upsert(PlayerSettings(id, doubleJumpEnabled = false))

        SetParticleVisibilityUseCase(repo).set(id, ParticleVisibility.OwnOnly)

        assertThat(repo.find(id)!!.doubleJumpEnabled).isFalse
    }
}
