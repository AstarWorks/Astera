package com.astarworks.astera.core.domain.visibility

/**
 * Vendor-neutral error surface for the packet backend (Wave 5y, ADR-0090).
 *
 * Wraps the failure modes that any packet-manipulation library — PacketEvents
 * in the chosen Phase 1 backend — can report up to use cases. Surfaced through
 * [VisibilityError.BackendError] and the packet-side
 * [com.astarworks.astera.core.domain.packet.PacketDecision] flow.
 *
 * Keeping this in `core.domain` (not `adapter.api`) is intentional: the
 * orchestrator wiring + tests need to pattern-match on the failure, and
 * forcing them through an adapter type would couple `core.application` to
 * Paper/PacketEvents loading order. ADR-0090's type-erasure rule still
 * applies — no PacketEvents import lands here.
 */
public sealed interface PacketBackendError {

    /**
     * Soft-dep [PacketEvents](https://github.com/retrooper/packetevents) is not
     * installed on the target server. Astera must remain bootable in vanilla
     * Paper (ADR-0009 / ADR-0015) — production code therefore catches this and
     * falls back to a no-op or scoreboard-team hide. Tests assert that callers
     * surface `PacketEventsUnavailable` instead of crashing.
     */
    public data object PacketEventsUnavailable : PacketBackendError

    /**
     * The backend was present but could not (de)serialise the wire payload for
     * the named packet type. Tests assert the [packetType] carries the
     * `PacketWrapper` simple name from PacketEvents (e.g. `WrapperPlayServerPlayerInfoRemove`)
     * so admins can grep server logs for the failing packet.
     */
    public data class SerializationFailure(val packetType: String) : PacketBackendError
}
