package com.astarworks.astera.core.domain.music

/**
 * Author-defined music track (Wave 5cinematic, ADR-0110).
 *
 * Loaded from `content/music/` YAML via
 * [com.astarworks.astera.core.application.contents.music.MusicTrackContents]
 * and exposed through the
 * [com.astarworks.astera.core.application.port.outbound.music.IMusicTrackRegistry]
 * port. The runtime [com.astarworks.astera.core.application.port.outbound.music.IMusicGateway]
 * resolves the track id at play time and reads [source] / [loop] /
 * [defaultVolume] to drive the platform-side audio backend.
 *
 * # Pure-value invariants
 *
 *  - [defaultVolume] is `0.0..1.0` (1.0 = full game audio volume).
 *  - [loop] = true plays seamlessly (cross-faded into itself); false fires
 *    [com.astarworks.astera.core.domain.event.MusicTrackEnded] when the
 *    track finishes naturally.
 */
public data class MusicTrack(
    public val id: MusicTrackId,
    public val displayName: String,
    public val source: MusicSource,
    public val license: MusicLicense,
    public val mood: MusicMood,
    public val defaultVolume: Double = 1.0,
    public val loop: Boolean = true,
    public val durationTicks: Long? = null,
) {
    init {
        require(defaultVolume in 0.0..1.0) {
            "MusicTrack.defaultVolume must be in 0.0..1.0 (got $defaultVolume)"
        }
        require(displayName.isNotBlank()) {
            "MusicTrack.displayName must not be blank"
        }
        durationTicks?.let {
            require(it >= 0L) {
                "MusicTrack.durationTicks must be >= 0 when set (got $it)"
            }
        }
    }
}
