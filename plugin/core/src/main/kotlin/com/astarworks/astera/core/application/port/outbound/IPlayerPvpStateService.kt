package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.pvp.PvpDisableReason

/**
 * Outbound port: per-player PvP toggle + canonical can-damage resolution
 * (Wave 5o, ADR-0081).
 *
 * Coexists with whatever region-based PvP rule a future world-env service
 * (Wave 5n, parallel branch) exposes — this port owns the *player-side*
 * override and is the single entry point combat-pipeline code asks before
 * applying damage between two players.
 *
 * # Semantics
 *
 * - `isEnabled(player)` is the per-player flag (default `true`). `enable` /
 *   `disable` toggle it; `disable` carries a [PvpDisableReason] that the
 *   audit listener uses to explain why the flag flipped.
 * - `resolveCanDamage(attacker, victim, position)` is the *combined* check:
 *   returns `false` if either side has the flag off, if the victim is in a
 *   spawn-protection window, or if [position] is inside a registered
 *   safe-zone (Wave 5n-style region overlay; the Wave 5o in-memory impl
 *   accepts a caller-supplied predicate so it can stand alone before
 *   Wave 5n lands).
 * - When `resolveCanDamage` returns `false`, the impl publishes a
 *   `PvpDamageBlocked` event so audit / analytics get the cause without the
 *   combat pipeline doing its own bookkeeping.
 */
public interface IPlayerPvpStateService {

    /** Current per-player flag for [playerId] (default `true`). */
    public fun isEnabled(playerId: PlayerId): Boolean

    /** Re-enable PvP for [playerId]. Idempotent. */
    public fun enable(playerId: PlayerId)

    /**
     * Disable PvP for [playerId], recording [reason] for audit. Idempotent
     * — calling twice with the same reason does not double-fire events.
     */
    public fun disable(playerId: PlayerId, reason: PvpDisableReason)

    /**
     * Combined PvP / spawn-protection / safe-zone check used by combat code
     * before applying damage between two players.
     *
     * Returns `true` if [attacker] is allowed to damage [victim] at
     * [position]; returns `false` after publishing a `PvpDamageBlocked`
     * event explaining why.
     */
    public fun resolveCanDamage(attacker: PlayerId, victim: PlayerId, position: Vec3): Boolean
}
