package com.astarworks.astera.core.domain.cinematic

/**
 * Opaque handle to an in-flight cinematic path playback session
 * (Wave 5cinematic, ADR-0110).
 *
 * Returned by [com.astarworks.astera.core.application.port.outbound.cinematic.ICinematicPathPlayer.play].
 * Callers store the handle to cancel mid-playback via
 * `ICinematicPathPlayer.cancel(handle)`.
 *
 * Distinct from [CameraHandle] because a cinematic playback is a higher-level
 * orchestration on top of one or more `ICameraGateway` calls — the path
 * player owns its own lifecycle ticker.
 */
@JvmInline
public value class CinematicHandle(public val id: String) {
    init {
        require(id.isNotBlank()) { "CinematicHandle.id must not be blank" }
    }
}
