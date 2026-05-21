package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: post-respawn invulnerability tracker (Wave 5o, ADR-0081).
 *
 * Grants a player a time-bounded "spawn protection" window after respawn
 * during which `IDamageGateway` / PvP checks must refuse incoming damage.
 * The Wave 5o `RespawnUseCase` calls [grantProtection] right after the
 * teleport-back; the Wave 5o `IPlayerPvpStateService.resolveCanDamage`
 * consults [isProtected] before letting an attacker deal damage; the
 * protection expires after `durationSeconds` server ticks via a scheduled
 * task that fires [revokeProtection] (which publishes
 * `PlayerProtectionExpired`).
 *
 * # InMemory impl semantics
 *
 * In-memory state only; no Postgres backing in Wave 5o. The Wave 6
 * Paper-side adapter may wire potion-effect glints / Adventure title
 * notifications on top of the same port. Idempotent: granting protection
 * to a player that already has it extends the window to the larger of
 * (remaining, new) — useful for "respawn-during-protection" edge cases
 * triggered by `/kill` while still protected.
 */
public interface ISpawnProtectionGateway {

    /**
     * Begin a [durationSeconds]-second protection window for [playerId].
     *
     * Idempotent — calling repeatedly extends to the longer of the two
     * remaining windows. A non-positive [durationSeconds] is a no-op (the
     * caller decided "no protection requested" and the gateway must not
     * surface an error).
     */
    public fun grantProtection(playerId: PlayerId, durationSeconds: Int)

    /** True if [playerId] currently has an active protection window. */
    public fun isProtected(playerId: PlayerId): Boolean

    /**
     * Remove [playerId]'s protection immediately. Idempotent: called by the
     * scheduled-expiry task as well as by tests / PvP-enable transitions
     * that need to drop the shield early.
     */
    public fun revokeProtection(playerId: PlayerId)
}
