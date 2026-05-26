package com.astarworks.astera.core.application.port.outbound.music

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.music.MusicMood
import com.astarworks.astera.core.domain.music.MusicTrack
import com.astarworks.astera.core.domain.music.MusicTrackId

/**
 * Outbound port: registry of author-defined music tracks loaded from
 * the `content/music/` YAML directory (Wave 5cinematic, ADR-0110).
 *
 * Extends the generic [Contents] surface so it slots into the existing
 * reload-dispatch substrate (`IReloadableContentsRegistry`,
 * `/astera reload music`).
 *
 * Adds [byMood] for the adaptive BGM service — it does not load tracks
 * itself, it asks the registry "give me all CINEMATIC tracks" and picks
 * one.
 */
public interface IMusicTrackRegistry : Contents<MusicTrackId, MusicTrack> {

    /**
     * All registered tracks whose [MusicTrack.mood] equals [mood].
     * Returns an empty list if no tracks have that mood (the adaptive BGM
     * service treats empty as "skip the trigger").
     */
    public fun byMood(mood: MusicMood): Collection<MusicTrack>
}
