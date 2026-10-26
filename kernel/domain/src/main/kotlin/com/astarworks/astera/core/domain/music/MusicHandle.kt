package com.astarworks.astera.core.domain.music

/**
 * Opaque handle to a playing music session (Wave 5cinematic, ADR-0110).
 *
 * Returned by [com.astarworks.astera.core.application.port.outbound.music.IMusicGateway.play].
 * Callers store the handle to drive `crossfade` / `duck` / `stop` against
 * a specific playback. Multiple handles can be live simultaneously
 * (overlay sting on top of looping match BGM = 2 handles).
 */
@JvmInline
public value class MusicHandle(public val id: String) {
    init {
        require(id.isNotBlank()) { "MusicHandle.id must not be blank" }
    }
}
