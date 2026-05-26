package com.astarworks.astera.core.application.service.music

import com.astarworks.astera.core.application.contents.music.MusicTrackContents
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.music.PerMatchBgmError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchMusicChanged
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.music.MusicLicense
import com.astarworks.astera.core.domain.music.MusicMood
import com.astarworks.astera.core.domain.music.MusicSource
import com.astarworks.astera.core.domain.music.MusicTrack
import com.astarworks.astera.core.domain.music.MusicTrackId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InMemoryPerMatchBgmServiceTest {

    private val matchId = MatchId("test-match")
    private lateinit var tracks: MusicTrackContents
    private lateinit var bus: InMemoryDomainEventBus
    private lateinit var captured: MutableList<MatchMusicChanged>
    private lateinit var service: InMemoryPerMatchBgmService

    private fun track(id: String): MusicTrack = MusicTrack(
        id = MusicTrackId(id),
        displayName = id,
        source = MusicSource.ResourcePack("astera:music/$id"),
        license = MusicLicense.RoyaltyFree(null),
        mood = MusicMood.MATCH,
    )

    @BeforeEach
    fun setup() {
        tracks = MusicTrackContents()
        tracks.register(track("track-a"))
        tracks.register(track("track-b"))
        bus = InMemoryDomainEventBus()
        captured = mutableListOf()
        bus.subscribe(MatchMusicChanged::class) { captured.add(it) }
        service = InMemoryPerMatchBgmService(tracks, bus, tickClock = { 200L })
    }

    @Test
    fun `setTrack unknown id fails`() {
        val r = service.setTrack(matchId, MusicTrackId("nope"))
        assertThat(r.isFailure).isTrue()
        assertThat((r as Result.Failure).error).isInstanceOf(PerMatchBgmError.UnknownTrack::class.java)
    }

    @Test
    fun `setTrack first-time records and publishes with null previous`() {
        service.setTrack(matchId, MusicTrackId("track-a"))
        assertThat(captured).hasSize(1)
        assertThat(captured[0].previousTrack).isNull()
        assertThat(captured[0].newTrack).isEqualTo(MusicTrackId("track-a"))
        assertThat(captured[0].atTick).isEqualTo(200L)
        assertThat(service.current(matchId)).isEqualTo(MusicTrackId("track-a"))
    }

    @Test
    fun `setTrack same as current is no-op`() {
        service.setTrack(matchId, MusicTrackId("track-a"))
        service.setTrack(matchId, MusicTrackId("track-a")) // same
        assertThat(captured).hasSize(1)
    }

    @Test
    fun `setTrack transition publishes with previous`() {
        service.setTrack(matchId, MusicTrackId("track-a"))
        service.setTrack(matchId, MusicTrackId("track-b"))
        assertThat(captured).hasSize(2)
        assertThat(captured[1].previousTrack).isEqualTo(MusicTrackId("track-a"))
        assertThat(captured[1].newTrack).isEqualTo(MusicTrackId("track-b"))
    }

    @Test
    fun `stop removes current entry`() {
        service.setTrack(matchId, MusicTrackId("track-a"))
        service.stop(matchId)
        assertThat(service.current(matchId)).isNull()
    }
}
