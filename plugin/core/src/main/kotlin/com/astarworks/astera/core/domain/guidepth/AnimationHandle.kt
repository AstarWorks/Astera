package com.astarworks.astera.core.domain.guidepth

/**
 * Opaque handle to an in-flight slot / hover / scroll animation
 * (Wave 5gui-depth, ADR-0114).
 *
 * Returned by [com.astarworks.astera.core.application.port.outbound.guidepth.IGuiAnimationController]
 * for every successful animate call. Use the handle to cancel an animation
 * before its natural end (e.g., player closed the GUI, slot got swapped under
 * the animation, scroll target changed mid-flight).
 *
 * Backed by a [String] (Wave 5cinematic [com.astarworks.astera.core.domain.cinematic.CameraHandle]
 * precedent) so the Paper adapter (Wave 6) can synthesise its own packet
 * correlation id without exposing it to the domain.
 */
@JvmInline
public value class AnimationHandle(public val id: String) {
    init {
        require(id.isNotBlank()) { "AnimationHandle.id must not be blank" }
    }
}
