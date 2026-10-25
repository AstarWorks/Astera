package com.astarworks.astera.core.domain.anticheat

/**
 * Outcome of a single movement-validation call. Three-state on purpose:
 *
 * - [Ok] — within physically plausible bounds; pass through.
 * - [Suspicious] — outside the heuristic envelope but not impossible; log /
 *   forward to [com.astarworks.astera.core.domain.anticheat.AnticheatSuspicion]
 *   subscribers, do not cancel.
 * - [Reject] — physically implausible; cancel the move and (optionally) roll
 *   the player back to [from] at the call site.
 *
 * Adopting a sealed result over an exception keeps the validator free of
 * raw JVM throwables and lets callers exhaustively branch (ADR-0083).
 */
public sealed interface MovementValidation {

    public data object Ok : MovementValidation

    public data class Suspicious(val reason: MovementSuspicion) : MovementValidation

    public data class Reject(val reason: MovementRejection) : MovementValidation
}
