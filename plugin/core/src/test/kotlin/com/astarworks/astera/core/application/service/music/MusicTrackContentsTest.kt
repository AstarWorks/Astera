package com.astarworks.astera.core.application.service.music

import com.astarworks.astera.core.application.contents.music.MusicTrackContents
import com.astarworks.astera.core.domain.music.MusicLicense
import com.astarworks.astera.core.domain.music.MusicMood
import com.astarworks.astera.core.domain.music.MusicSource
import com.astarworks.astera.core.domain.music.MusicTrack
import com.astarworks.astera.core.domain.music.MusicTrackId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MusicTrackContentsTest {

    private fun track(id: String, mood: MusicMood): MusicTrack = MusicTrack(
        id = MusicTrackId(id),
        displayName = id,
        source = MusicSource.ResourcePack("astera:music/$id"),
        license = MusicLicense.RoyaltyFree(null),
        mood = mood,
    )

    @Test
    fun `register + find round trip`() {
        val r = MusicTrackContents()
        val t = track("battle-a", MusicMood.MATCH)
        r.register(t)
        assertThat(r.find(t.id)).isEqualTo(t)
    }

    @Test
    fun `byMood groups by mood and ignores others`() {
        val r = MusicTrackContents()
        r.register(track("lobby-a", MusicMood.LOBBY))
        r.register(track("match-a", MusicMood.MATCH))
        r.register(track("match-b", MusicMood.MATCH))
        r.register(track("boss-a", MusicMood.BOSS_REVEAL))

        assertThat(r.byMood(MusicMood.MATCH).map { it.id })
            .containsExactlyInAnyOrder(MusicTrackId("match-a"), MusicTrackId("match-b"))
        assertThat(r.byMood(MusicMood.LOBBY).map { it.id })
            .containsExactly(MusicTrackId("lobby-a"))
        assertThat(r.byMood(MusicMood.VICTORY)).isEmpty()
    }
}
