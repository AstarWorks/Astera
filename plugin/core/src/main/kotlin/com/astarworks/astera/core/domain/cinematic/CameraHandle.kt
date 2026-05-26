package com.astarworks.astera.core.domain.cinematic

/**
 * Opaque handle to a camera lock / pan / shake / zoom session
 * (Wave 5cinematic, ADR-0110).
 *
 * Returned by [com.astarworks.astera.core.application.port.outbound.cinematic.ICameraGateway.lockCamera]
 * and similar. Use cases pass the handle through to subsequent calls
 * (`releaseCamera`, `panCamera(handle, ...)` if the caller wants to extend
 * an existing lock).
 *
 * Backed by a [String] (Wave 5h hologram-handle precedent) so the Paper
 * adapter can synthesise its own packet correlation id without exposing it
 * to the domain.
 */
@JvmInline
public value class CameraHandle(public val id: String) {
    init {
        require(id.isNotBlank()) { "CameraHandle.id must not be blank" }
    }
}
