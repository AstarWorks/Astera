package com.astarworks.astera.core.domain.pvp

/**
 * Why an `IPlayerPvpStateService.resolveCanDamage` call returned `false`
 * (Wave 5o, ADR-0081).
 *
 * Sealed so the combat / audit listeners can branch exhaustively on the
 * cause. Each variant is a `data object` because the cause is parameter-free
 * today — the position + player ids that the audit `PvpDamageBlocked` event
 * carries are enough context for the consumer side, and keeping the variants
 * objects lets the matrix-style PvP service reuse single instances per
 * resolution call.
 */
public sealed interface PvpDisableReason {

    /** Victim is inside a post-respawn spawn-protection window. */
    public data object SpawnProtection : PvpDisableReason

    /** Attacker or victim is standing in a region whose PvP rule is OFF. */
    public data object SafeZone : PvpDisableReason

    /**
     * The victim is a new-player whose grace period has not yet elapsed.
     * Wave 5o ships the variant; the new-player grace tracker arrives in a
     * later wave — for now the service never returns this branch.
     */
    public data object NewPlayer : PvpDisableReason

    /** An admin explicitly disabled PvP for the victim (e.g. /pvp off). */
    public data object AdminOverride : PvpDisableReason
}
