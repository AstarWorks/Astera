package com.astarworks.astera.core.domain.cinematic

/**
 * Opaque handle to an in-flight screen fade / freeze session
 * (Wave 5cinematic, ADR-0110).
 *
 * Returned by
 * [com.astarworks.astera.core.application.port.outbound.cinematic.IScreenEffectGateway.fade]
 * and `freeze`. Callers store the handle to cancel an effect early
 * (e.g. skip-cutscene button).
 */
@JvmInline
public value class ScreenEffectHandle(public val id: String) {
    init {
        require(id.isNotBlank()) { "ScreenEffectHandle.id must not be blank" }
    }
}
