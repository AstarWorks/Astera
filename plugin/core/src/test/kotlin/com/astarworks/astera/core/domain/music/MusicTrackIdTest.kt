package com.astarworks.astera.core.domain.music

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MusicTrackIdTest {

    @Test
    fun `kebab case accepted`() {
        assertThat(MusicTrackId("lobby-loop").value).isEqualTo("lobby-loop")
        assertThat(MusicTrackId("a").value).isEqualTo("a")
        assertThat(MusicTrackId("battle-theme-02").value).isEqualTo("battle-theme-02")
    }

    @Test
    fun `uppercase rejected`() {
        assertThatThrownBy { MusicTrackId("Lobby-Loop") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `underscore rejected`() {
        assertThatThrownBy { MusicTrackId("lobby_loop") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `leading dash rejected`() {
        assertThatThrownBy { MusicTrackId("-lobby") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `trailing dash rejected`() {
        assertThatThrownBy { MusicTrackId("lobby-") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `empty rejected`() {
        assertThatThrownBy { MusicTrackId("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
