package com.astarworks.astera.core.domain.packet

/**
 * Decision returned by an [com.astarworks.astera.core.application.port.outbound.packet.IPacketInterceptor]
 * listener (Wave 5y, ADR-0095).
 *
 * Three-state on purpose:
 *
 * - [Pass] — leave the packet untouched. Idiomatic 95% return value for
 *   read-only observers (metrics, anti-cheat heuristics that only forward
 *   the suspicion to [com.astarworks.astera.core.domain.event.AnticheatFlagRaised]).
 * - [Cancel] — drop the packet entirely. Used by the Wave 5q movement
 *   validator bridge when [com.astarworks.astera.core.domain.anticheat.MovementValidation.Reject]
 *   fires, and by the per-viewer visibility gateway to suppress spawn packets
 *   for hidden entities.
 * - [Replace] — substitute the wire payload with [newPayload]. Constrained:
 *   the keyset of [newPayload] MUST be a subset of the original envelope's
 *   payload (the Wave 6b adapter rejects mismatches and surfaces
 *   [com.astarworks.astera.core.domain.visibility.PacketBackendError.SerializationFailure]).
 *   Used for chat-channel scoping (replace the recipient set) and for masking
 *   coordinates in spectator replays.
 *
 * Sealed so the Wave 6b adapter must exhaustively branch.
 */
public sealed interface PacketDecision {

    public data object Pass : PacketDecision

    public data object Cancel : PacketDecision

    public data class Replace(val newPayload: Map<String, Any>) : PacketDecision
}
