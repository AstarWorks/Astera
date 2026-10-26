package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.hologram.HologramHandle
import com.astarworks.astera.core.domain.hologram.transition.HologramTransitionHandle
import java.time.Instant

/**
 * Cross-plugin event (Wave 5transitions, ADR-0115 §G): a hologram
 * transition was cancelled before its natural completion.
 *
 * Fires from
 * [com.astarworks.astera.core.application.port.outbound.hologram.IHologramTransitionController.cancel]
 * /
 * [com.astarworks.astera.core.application.port.outbound.hologram.IHologramTransitionController.cancelAll]
 * and also when the controller implicitly cancels (hologram despawned,
 * scheduler shut down, or timeline interrupted by another).
 *
 * Mutually exclusive with [HologramTransitionCompleted].
 *
 * @property reasonKind snake_case discriminator — `"manual" /
 *   "hologram_despawned" / "timeline_completed" / "scheduler_shutdown"`
 *   (matching the `reasonKind` pattern from `CinematicEnded` /
 *   `IslandUnloaded` / `SlotAnimationCancelled`).
 */
public data class HologramTransitionCancelled(
    val handle: HologramHandle,
    val transitionHandle: HologramTransitionHandle,
    val reasonKind: String,
    val atTick: Long = 0L,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
