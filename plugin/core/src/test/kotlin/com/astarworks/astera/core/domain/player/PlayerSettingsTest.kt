package com.astarworks.astera.core.domain.player

import com.astarworks.astera.core.domain.i18n.LocaleCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave G 5player-prefs-social (ADR-0124) — invariants for [PlayerSettings]'s
 * new fields. The `init` block must reject out-of-range volumes; [volume]
 * / [withVolume] must be the single source of truth for the
 * channel-to-field mapping.
 */
class PlayerSettingsTest {

    private fun playerId(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `default settings have EN locale and 100 percent volume on every bus`() {
        val s = PlayerSettings.default(playerId())

        assertThat(s.locale).isEqualTo(LocaleCode.EN)
        assertThat(s.musicVolume).isEqualTo(100)
        assertThat(s.soundVolume).isEqualTo(100)
        assertThat(s.voiceVolume).isEqualTo(100)
    }

    @Test
    fun `init block rejects musicVolume above 100`() {
        assertThatThrownBy {
            PlayerSettings(playerId(), musicVolume = 101)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `init block rejects negative soundVolume`() {
        assertThatThrownBy {
            PlayerSettings(playerId(), soundVolume = -1)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `init block rejects out-of-range voiceVolume`() {
        assertThatThrownBy {
            PlayerSettings(playerId(), voiceVolume = 200)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `volume reads the right field per channel`() {
        val s = PlayerSettings(playerId(), musicVolume = 10, soundVolume = 20, voiceVolume = 30)

        assertThat(s.volume(VolumeChannel.Music)).isEqualTo(10)
        assertThat(s.volume(VolumeChannel.Sound)).isEqualTo(20)
        assertThat(s.volume(VolumeChannel.Voice)).isEqualTo(30)
    }

    @Test
    fun `withVolume only mutates the targeted channel`() {
        val before = PlayerSettings(playerId(), musicVolume = 100, soundVolume = 100, voiceVolume = 100)

        val after = before.withVolume(VolumeChannel.Sound, 42)

        assertThat(after.musicVolume).isEqualTo(100)
        assertThat(after.soundVolume).isEqualTo(42)
        assertThat(after.voiceVolume).isEqualTo(100)
    }

    @Test
    fun `SUPPORTED_LOCALES contains EN and JA`() {
        assertThat(PlayerSettings.SUPPORTED_LOCALES).containsExactlyInAnyOrder(LocaleCode.EN, LocaleCode.JA)
    }

    @Test
    fun `boundary volumes 0 and 100 are accepted`() {
        // No exception.
        PlayerSettings(playerId(), musicVolume = 0, soundVolume = 0, voiceVolume = 0)
        PlayerSettings(playerId(), musicVolume = 100, soundVolume = 100, voiceVolume = 100)
    }
}
