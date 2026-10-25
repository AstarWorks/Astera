package com.astarworks.astera.core.application.contents.music

import com.astarworks.astera.core.application.config.music.MusicTrackYamlConfig
import com.astarworks.astera.core.application.contents.YamlDynamicContents
import com.astarworks.astera.core.application.port.outbound.music.IMusicTrackRegistry
import com.astarworks.astera.core.domain.music.MusicMood
import com.astarworks.astera.core.domain.music.MusicTrack
import com.astarworks.astera.core.domain.music.MusicTrackId

/**
 * Music-side `Contents<MusicTrackId, MusicTrack>` (Wave 5cinematic, ADR-0110).
 *
 * Mirrors [com.astarworks.astera.core.application.contents.ParticleContents]:
 * subclass of [YamlDynamicContents] that registers as the
 * [IMusicTrackRegistry] port. Inherits hot-reload semantics
 * (`/astera reload music` triggers atomic swap).
 *
 * # Wire format
 *
 * See [MusicTrackYamlConfig] for the YAML shape. Each file under the
 * `content/music/` directory defines exactly one track.
 *
 * # byMood implementation
 *
 * Linear scan over `all()`. Track count is expected to stay modest
 * (single-digit per mood at Phase 1); if growth pressures this, a Phase 4
 * follow-up can swap to a pre-computed mood index.
 */
public class MusicTrackContents : YamlDynamicContents<MusicTrackId, MusicTrack, MusicTrackYamlConfig>(
    categoryName = "music",
    deserializer = MusicTrackYamlConfig.serializer(),
),
    IMusicTrackRegistry {

    override fun toEntry(config: MusicTrackYamlConfig): Pair<MusicTrackId, MusicTrack> {
        val track = config.toSpec()
        return track.id to track
    }

    /**
     * Convenience overload: register by track alone (the track owns its id).
     * Used by `AsteraTestHarness.withMusic(...)` and admin commands that
     * build a [MusicTrack] in memory.
     */
    public fun register(track: MusicTrack) {
        register(track.id, track)
    }

    override fun byMood(mood: MusicMood): Collection<MusicTrack> =
        all().filter { it.mood == mood }
}
