package com.astarworks.astera.core.domain.hologram.transition

/**
 * Opaque handle to a live transition managed by
 * [com.astarworks.astera.core.application.port.outbound.hologram.IHologramTransitionController]
 * (Wave 5transitions, ADR-0115 §E).
 *
 * Distinct from
 * [com.astarworks.astera.core.domain.hologram.HologramHandle] — a hologram
 * may have multiple transitions running concurrently (e.g. a `Composite`
 * fade-in + slide-up), each with its own [HologramTransitionHandle]. The
 * controller resolves the parent hologram from this handle when needed.
 *
 * Stable for the controller impl's lifetime, not portable across plugin
 * restarts (transitions are ephemeral by design — no persistence in Wave
 * 5transitions, see ADR-0115 §F constraints).
 */
@JvmInline
public value class HologramTransitionHandle(public val value: String) {
    init {
        require(value.isNotBlank()) { "HologramTransitionHandle must not be blank" }
    }

    override fun toString(): String = value
}
