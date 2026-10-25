package com.astarworks.astera.core.domain.guidepth

/**
 * Opaque handle to a player-visible modal / popup / overlay
 * (Wave 5gui-depth, ADR-0114).
 *
 * Returned by [com.astarworks.astera.core.application.port.outbound.guidepth.IGuiModalGateway.showModal].
 * Use the handle to dismiss the modal early (`dismissModal(handle)`) or to
 * correlate adapter-level events with the originating call.
 *
 * Backed by a [String] (Wave 5cinematic [com.astarworks.astera.core.domain.cinematic.CameraHandle]
 * precedent) so the Paper adapter (Wave 6) can synthesise its own packet
 * correlation id without exposing it to the domain.
 */
@JvmInline
public value class ModalHandle(public val id: String) {
    init {
        require(id.isNotBlank()) { "ModalHandle.id must not be blank" }
    }
}
