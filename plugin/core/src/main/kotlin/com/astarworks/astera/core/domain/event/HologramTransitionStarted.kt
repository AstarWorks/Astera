package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.hologram.HologramHandle
import com.astarworks.astera.core.domain.hologram.transition.HologramTransitionHandle
import java.time.Instant

/**
 * Cross-plugin event (Wave 5transitions, ADR-0115 §G): a hologram
 * transition has just begun.
 *
 * Fired by
 * [com.astarworks.astera.core.application.port.outbound.hologram.IHologramTransitionController.play]
 * immediately after the controller records the handle. For composite
 * parents the event fires for the parent handle; child transitions do
 * NOT publish their own start events (would flood at scale).
 *
 * @property handle the hologram the transition is animating.
 * @property transitionHandle the controller-minted handle for cancellation.
 * @property atTick scheduler tick at the moment of publish.
 */
public data class HologramTransitionStarted(
    val handle: HologramHandle,
    val transitionHandle: HologramTransitionHandle,
    val atTick: Long = 0L,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
