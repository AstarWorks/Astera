package com.astarworks.astera.core.application.usecase.settings

import com.astarworks.astera.core.application.service.InMemoryPlayerSettingsRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class SetLocaleUseCaseTest {

    private fun playerId(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `set switches locale to ja on a fresh player`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()

        val outcome = SetLocaleUseCase(repo).set(id, LocaleCode.JA)

        assertThat(outcome).isInstanceOf(Result.Success::class.java)
        assertThat(repo.find(id)!!.locale).isEqualTo(LocaleCode.JA)
    }

    @Test
    fun `set preserves other fields when changing locale`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()
        repo.upsert(
            PlayerSettings(id, musicVolume = 25, soundVolume = 75, doubleJumpEnabled = false),
        )

        SetLocaleUseCase(repo).set(id, LocaleCode.JA)

        val current = repo.find(id)!!
        assertThat(current.locale).isEqualTo(LocaleCode.JA)
        assertThat(current.musicVolume).isEqualTo(25)
        assertThat(current.soundVolume).isEqualTo(75)
        assertThat(current.doubleJumpEnabled).isFalse
    }

    @Test
    fun `set rejects locales outside SUPPORTED_LOCALES`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()
        val unsupported = LocaleCode("fr")

        val outcome = SetLocaleUseCase(repo).set(id, unsupported)

        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        val err = (outcome as Result.Failure).error
        assertThat(err).isInstanceOf(SettingsError.UnsupportedLocale::class.java)
        assertThat((err as SettingsError.UnsupportedLocale).locale).isEqualTo(unsupported)
        // The row is untouched on failure
        assertThat(repo.find(id)).isNull()
    }

    @Test
    fun `set accepts EN as a no-op-style switch`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()

        val outcome = SetLocaleUseCase(repo).set(id, LocaleCode.EN)

        assertThat(outcome).isInstanceOf(Result.Success::class.java)
        assertThat(repo.find(id)!!.locale).isEqualTo(LocaleCode.EN)
    }
}
