package com.astarworks.astera.core.application.usecase.settings

import com.astarworks.astera.core.application.service.InMemoryPlayerSettingsRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.VolumeChannel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class SetVolumeUseCaseTest {

    private fun playerId(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `set music volume on fresh player upserts default-merged row`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()

        val outcome = SetVolumeUseCase(repo).set(id, VolumeChannel.Music, 50)

        assertThat(outcome).isInstanceOf(Result.Success::class.java)
        val row = repo.find(id)!!
        assertThat(row.musicVolume).isEqualTo(50)
        assertThat(row.soundVolume).isEqualTo(100)
        assertThat(row.voiceVolume).isEqualTo(100)
    }

    @Test
    fun `each channel is independent`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()
        val useCase = SetVolumeUseCase(repo)

        useCase.set(id, VolumeChannel.Music, 10)
        useCase.set(id, VolumeChannel.Sound, 20)
        useCase.set(id, VolumeChannel.Voice, 30)

        val row = repo.find(id)!!
        assertThat(row.musicVolume).isEqualTo(10)
        assertThat(row.soundVolume).isEqualTo(20)
        assertThat(row.voiceVolume).isEqualTo(30)
    }

    @Test
    fun `set rejects values above 100`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()

        val outcome = SetVolumeUseCase(repo).set(id, VolumeChannel.Music, 101)

        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        val err = (outcome as Result.Failure).error
        assertThat(err).isInstanceOf(SettingsError.InvalidVolume::class.java)
        assertThat((err as SettingsError.InvalidVolume).value).isEqualTo(101)
        assertThat(repo.find(id)).isNull()
    }

    @Test
    fun `set rejects negative values`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()

        val outcome = SetVolumeUseCase(repo).set(id, VolumeChannel.Sound, -1)

        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        assertThat(repo.find(id)).isNull()
    }

    @Test
    fun `set accepts boundary values 0 and 100`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()
        val useCase = SetVolumeUseCase(repo)

        useCase.set(id, VolumeChannel.Music, 0)
        assertThat(repo.find(id)!!.musicVolume).isEqualTo(0)

        useCase.set(id, VolumeChannel.Music, 100)
        assertThat(repo.find(id)!!.musicVolume).isEqualTo(100)
    }
}
