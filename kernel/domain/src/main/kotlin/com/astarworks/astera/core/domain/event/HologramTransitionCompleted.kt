package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.hologram.HologramHandle
import com.astarworks.astera.core.domain.hologram.transition.HologramTransitionHandle
import java.time.Instant

/**
 * Cross-plugin event (Wave 5transitions, ADR-0115 §G): a hologram
 * transition finished naturally (its `durationTicks` / `delayTicks`
 * elapsed).
 *
 * Mutually exclusive with [HologramTransitionCancelled] — every transition
 * publishes exactly one of these two events. For looped timelines
 * (`LoopMode.Loop(times)` / `LoopMode.PingPong(times)`) the event fires
 * **once at the end of the whole loop body** (the last cycle), not on every
 * cycle — otherwise subscribers would be flooded for infinite loops.
 *
 * Infinite-loop timelines (`times = null`) only ever publish
 * [HologramTransitionStarted] + (eventually) [HologramTransitionCancelled];
 * they never publish this event by design.
 *
 * @property handle the hologram the transition was animating.
 * @property transitionHandle the controller-minted handle (now revoked).
 * @property atTick scheduler tick at the moment of publish.
 */
public data class HologramTransitionCompleted(
    val handle: HologramHandle,
    val transitionHandle: HologramTransitionHandle,
    val atTick: Long = 0L,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
