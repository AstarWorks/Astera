package com.astarworks.astera.core.application.service.music

import com.astarworks.astera.core.application.contents.music.MusicTrackContents
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.music.AdaptiveBgmError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.AdaptiveMusicTriggered
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

class InMemoryAdaptiveBgmServiceTest {

    private val matchId = MatchId("test-match")
    private lateinit var tracks: MusicTrackContents
    private lateinit var bus: InMemoryDomainEventBus
    private lateinit var perMatch: InMemoryPerMatchBgmService
    private lateinit var adaptive: InMemoryAdaptiveBgmService
    private lateinit var adaptiveCaptured: MutableList<AdaptiveMusicTriggered>
    private lateinit var matchCaptured: MutableList<MatchMusicChanged>

    private fun track(id: String, mood: MusicMood): MusicTrack = MusicTrack(
        id = MusicTrackId(id),
        displayName = id,
        source = MusicSource.ResourcePack("astera:music/$id"),
        license = MusicLicense.RoyaltyFree(null),
        mood = mood,
    )

    @BeforeEach
    fun setup() {
        tracks = MusicTrackContents()
        tracks.register(track("boss-1", MusicMood.BOSS_REVEAL))
        tracks.register(track("hi-1", MusicMood.HIGH_INTENSITY))
        // Note: no track with mood = VICTORY — we test the empty case
        bus = InMemoryDomainEventBus()
        adaptiveCaptured = mutableListOf()
        matchCaptured = mutableListOf()
        bus.subscribe(AdaptiveMusicTriggered::class) { adaptiveCaptured.add(it) }
        bus.subscribe(MatchMusicChanged::class) { matchCaptured.add(it) }
        perMatch = InMemoryPerMatchBgmService(tracks, bus, tickClock = { 99L })
        adaptive = InMemoryAdaptiveBgmService(tracks, perMatch, bus, tickClock = { 100L })
    }

    @Test
    fun `invalid reasonKind is rejected`() {
        val r = adaptive.trigger(matchId, MusicMood.BOSS_REVEAL, reasonKind = "not_a_reason")
        assertThat(r.isFailure).isTrue()
        assertThat((r as Result.Failure).error).isInstanceOf(AdaptiveBgmError.InvalidReason::class.java)
    }

    @Test
    fun `valid trigger publishes both events and updates per-match`() {
        adaptive.trigger(matchId, MusicMood.BOSS_REVEAL, reasonKind = "phase_transition")
        assertThat(adaptiveCaptured).hasSize(1)
        assertThat(adaptiveCaptured[0].newMood).isEqualTo(MusicMood.BOSS_REVEAL)
        assertThat(adaptiveCaptured[0].selectedTrack).isEqualTo(MusicTrackId("boss-1"))
        assertThat(adaptiveCaptured[0].reasonKind).isEqualTo("phase_transition")
        assertThat(matchCaptured).hasSize(1)
        assertThat(perMatch.current(matchId)).isEqualTo(MusicTrackId("boss-1"))
    }

    @Test
    fun `mood with no tracks is silently skipped`() {
        val r = adaptive.trigger(matchId, MusicMood.VICTORY, reasonKind = "manual")
        assertThat(r.isSuccess).isTrue()
        assertThat(adaptiveCaptured).isEmpty()
        assertThat(matchCaptured).isEmpty()
    }

    @Test
    fun `all four reason kinds accepted`() {
        for (reason in listOf("hp_threshold", "phase_transition", "player_count", "manual")) {
            val r = adaptive.trigger(matchId, MusicMood.HIGH_INTENSITY, reason)
            assertThat(r.isSuccess).isTrue()
        }
    }
}
