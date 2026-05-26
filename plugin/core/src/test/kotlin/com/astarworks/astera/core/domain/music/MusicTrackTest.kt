package com.astarworks.astera.core.domain.music

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MusicTrackTest {

    private fun track(
        id: String = "battle-theme",
        volume: Double = 1.0,
        loop: Boolean = true,
        durationTicks: Long? = null,
    ): MusicTrack = MusicTrack(
        id = MusicTrackId(id),
        displayName = "Battle Theme",
        source = MusicSource.ResourcePack("astera:music/battle"),
        license = MusicLicense.RoyaltyFree("CC0"),
        mood = MusicMood.MATCH,
        defaultVolume = volume,
        loop = loop,
        durationTicks = durationTicks,
    )

    @Test
    fun `valid track constructs`() {
        val t = track()
        assertThat(t.id).isEqualTo(MusicTrackId("battle-theme"))
        assertThat(t.loop).isTrue()
    }

    @Test
    fun `volume above ceiling rejected`() {
        assertThatThrownBy { track(volume = 1.5) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `volume below floor rejected`() {
        assertThatThrownBy { track(volume = -0.1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `negative duration rejected`() {
        assertThatThrownBy { track(durationTicks = -1L) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `null duration accepted (unknown length)`() {
        assertThat(track(durationTicks = null).durationTicks).isNull()
    }
}
