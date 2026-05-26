package com.astarworks.astera.core.application.service.music

import com.astarworks.astera.core.application.contents.music.MusicTrackContents
import com.astarworks.astera.core.application.port.outbound.music.MusicError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.music.MusicLicense
import com.astarworks.astera.core.domain.music.MusicMood
import com.astarworks.astera.core.domain.music.MusicSource
import com.astarworks.astera.core.domain.music.MusicTrack
import com.astarworks.astera.core.domain.music.MusicTrackId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryMusicGatewayTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private lateinit var tracks: MusicTrackContents
    private lateinit var gateway: InMemoryMusicGateway

    private fun track(id: String, mood: MusicMood = MusicMood.MATCH): MusicTrack = MusicTrack(
        id = MusicTrackId(id),
        displayName = "Test $id",
        source = MusicSource.ResourcePack("astera:music/$id"),
        license = MusicLicense.RoyaltyFree(null),
        mood = mood,
    )

    @BeforeEach
    fun setup() {
        tracks = MusicTrackContents()
        tracks.register(track("battle-a"))
        tracks.register(track("battle-b"))
        gateway = InMemoryMusicGateway(tracks)
    }

    @Test
    fun `play unknown track fails`() {
        val r = gateway.play(playerId, MusicTrackId("does-not-exist"), 1.0)
        assertThat(r.isFailure).isTrue()
        assertThat((r as Result.Failure).error).isInstanceOf(MusicError.UnknownTrack::class.java)
    }

    @Test
    fun `play returns handle and records active`() {
        val r = gateway.play(playerId, MusicTrackId("battle-a"), 0.7)
        assertThat(r.isSuccess).isTrue()
        val handle = (r as Result.Success).value
        assertThat(gateway.active(playerId)).contains(handle)
    }

    @Test
    fun `play rejects out-of-range volume`() {
        val r = gateway.play(playerId, MusicTrackId("battle-a"), volume = 1.5)
        assertThat(r.isFailure).isTrue()
        assertThat((r as Result.Failure).error).isInstanceOf(MusicError.InvalidVolume::class.java)
    }

    @Test
    fun `crossfade requires known source handle`() {
        val r = gateway.crossfade(
            fromHandle = com.astarworks.astera.core.domain.music.MusicHandle(UUID.randomUUID().toString()),
            toTrack = MusicTrackId("battle-b"),
            durationTicks = 20,
        )
        assertThat(r.isFailure).isTrue()
        assertThat((r as Result.Failure).error).isInstanceOf(MusicError.UnknownHandle::class.java)
    }

    @Test
    fun `crossfade replaces active handle`() {
        val first = (gateway.play(playerId, MusicTrackId("battle-a"), 1.0) as Result.Success).value
        val second = (gateway.crossfade(first, MusicTrackId("battle-b"), 20) as Result.Success).value
        val active = gateway.active(playerId)
        assertThat(active).contains(second)
        assertThat(active).doesNotContain(first)
    }

    @Test
    fun `duck on unknown handle fails`() {
        val r = gateway.duck(
            com.astarworks.astera.core.domain.music.MusicHandle("nope"),
            factor = 0.3,
            durationTicks = 20,
        )
        assertThat(r.isFailure).isTrue()
    }

    @Test
    fun `duck and unduck succeed on known handle`() {
        val h = (gateway.play(playerId, MusicTrackId("battle-a"), 1.0) as Result.Success).value
        assertThat(gateway.duck(h, 0.3, 20).isSuccess).isTrue()
        assertThat(gateway.unduck(h, 20).isSuccess).isTrue()
    }

    @Test
    fun `stop removes handle and is idempotent`() {
        val h = (gateway.play(playerId, MusicTrackId("battle-a"), 1.0) as Result.Success).value
        assertThat(gateway.stop(h, fadeOutTicks = 0).isSuccess).isTrue()
        assertThat(gateway.active(playerId)).doesNotContain(h)
        assertThat(gateway.stop(h, fadeOutTicks = 0).isSuccess).isTrue()
    }
}
