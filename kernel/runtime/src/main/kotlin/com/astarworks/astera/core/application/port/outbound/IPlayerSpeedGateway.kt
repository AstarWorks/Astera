package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.SpeedAxis

/**
 * Outbound port: adjust a player's walk / fly speed multipliers and toggle
 * flight / sprinting / no-clip with TTL (Wave 5k, ADR-0080).
 *
 * Distinct from Wave 5g `IPlayerMovementGateway` (gravity multiplier only).
 * Where [IPlayerMovementGateway] focuses on physics weight, this port owns
 * the avatar's locomotion knobs that Bukkit exposes as direct setters on
 * `Player`:
 *
 *   - `Player.walkSpeed`
 *   - `Player.flySpeed`
 *   - `Player.allowFlight`
 *   - `Player.isFlying`
 *   - `Player.isSprinting`
 *   - per-game-mode "no-clip" (only available on Bukkit `GameMode.SPECTATOR`
 *     or via `Player.setCollidable(false)` workarounds — the Wave 6a adapter
 *     decides which).
 *
 * # TTL semantics
 *
 * Every mutator takes `ttlTicks`. After expiry the value is reset to its
 * **baseline** (multiplier 1.0, no-flight, no-sprinting, no-no-clip). Pass
 * `Int.MAX_VALUE` for "indefinite until reset()". Follows Wave 5g
 * [IPlayerMovementGateway] precedent so the resulting Paper adapter can
 * reuse the same scheduler-based expiry helper.
 *
 * Spectator-mode toggle is intentionally NOT included here — that's
 * Wave 5h `ISpectatorGateway` (separate concern: visibility, vanish-target
 * tracking, GameMode switch).
 */
public interface IPlayerSpeedGateway {

    /**
     * Set [playerId]'s [axis] speed multiplier to [multiplier] for [ttlTicks]
     * in-game ticks.
     *
     * `multiplier = 1.0` is normal. Implementations may clamp the Bukkit-side
     * value (`Player.walkSpeed` accepts -1.0..1.0; baseline 0.2 means the
     * effective range is roughly 0..5x without overflow).
     *
     * Fails with [SpeedMutationError.InvalidValue] when [multiplier] is
     * non-finite or negative; or when [ttlTicks] <= 0 and not MAX_VALUE.
     */
    public fun setMultiplier(
        playerId: PlayerId,
        axis: SpeedAxis,
        multiplier: Double,
        ttlTicks: Int,
    ): Result<Unit, SpeedMutationError>

    /** Immediately reset [playerId]'s [axis] multiplier to 1.0. */
    public fun resetMultiplier(playerId: PlayerId, axis: SpeedAxis)

    /**
     * Allow / disallow flight for [playerId] for [ttlTicks] ticks. After
     * expiry the value resets to `false`.
     */
    public fun setAllowFlight(playerId: PlayerId, allow: Boolean, ttlTicks: Int): Result<Unit, SpeedMutationError>

    /**
     * Force [playerId]'s sprint state for [ttlTicks] ticks. After expiry the
     * value resets to `false`. (The Paper adapter calls `Player.isSprinting =
     * sprinting`; vanilla input may immediately flip it back if the player
     * isn't holding sprint — Phase 1 PoC accepts that trade-off.)
     */
    public fun setSprinting(playerId: PlayerId, sprinting: Boolean, ttlTicks: Int): Result<Unit, SpeedMutationError>

    /**
     * Toggle no-clip for [playerId] for [ttlTicks] ticks (admin only). After
     * expiry the value resets to `false`.
     *
     * Spectator mode is the separate `ISpectatorGateway` (Wave 5h); this
     * port handles the "admin walks through walls without changing gamemode"
     * use case via the Paper adapter's `Player.setCollidable(false)` path.
     */
    public fun setNoClip(playerId: PlayerId, noClip: Boolean, ttlTicks: Int): Result<Unit, SpeedMutationError>

    /** Current multiplier for [axis], or `null` if no override active. */
    public fun currentMultiplier(playerId: PlayerId, axis: SpeedAxis): Double?

    /** Whether flight is currently allowed for [playerId]. */
    public fun isFlightAllowed(playerId: PlayerId): Boolean

    /** Whether the gateway has forced sprint on for [playerId]. */
    public fun isSprintingForced(playerId: PlayerId): Boolean

    /** Whether no-clip is currently active for [playerId]. */
    public fun isNoClipActive(playerId: PlayerId): Boolean
}

/** Errors returnable from [IPlayerSpeedGateway] mutations. */
public sealed interface SpeedMutationError {

    /** Numeric argument was non-finite, negative, or zero where positive is required. */
    public data class InvalidValue(val field: String, val value: Double) : SpeedMutationError

    /** Backend rejected the mutation (Paper adapter: player offline). */
    public data class BackendUnavailable(val reason: String) : SpeedMutationError
}
