package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: mutate a player's hunger bar (Wave 5k, ADR-0080).
 *
 * # Why this exists
 *
 * Astera does not use the vanilla hunger mechanic in Phase 1 — Hypixel-style
 * Annihilation has no eating loop. The hunger bar real-estate is repurposed
 * as one of two ways to render resource state:
 *
 *  1. **Suppress** ([suppressHungerHud]) — `foodLevel = 20` + saturation max
 *     so the bar sits full and untouched; the Wave 5j (future) custom HUD
 *     renders the resource bar via a BossBar or scoreboard slot. This is the
 *     production default once the custom HUD lands.
 *  2. **Re-purpose as mana / resource bar** ([setHungerAsMana]) — map a
 *     0..1 resource ratio onto the 0..20 food gauge. Fallback for clients
 *     that disable resource pack delivery or for the pre-HUD Phase 1 PoC.
 *
 * Use one path or the other; mixing them produces visual flicker. The
 * [isHungerSuppressed] flag captures the current mode and listeners assume
 * it.
 */
public interface IPlayerFoodGateway {

    /**
     * Lock [playerId]'s hunger bar at full (foodLevel = 20, saturation = 20).
     *
     * Idempotent. Implementations do NOT install a tick-by-tick watchdog —
     * the Paper adapter relies on `FoodLevelChangeEvent` cancellation in
     * Wave 6a. The InMemory default just flips an internal boolean.
     */
    public fun suppressHungerHud(playerId: PlayerId, suppress: Boolean = true): Result<Unit, FoodMutationError>

    /**
     * Set the food gauge to the integer derived from [manaPercent].
     *
     * `manaPercent` is in `[0.0, 1.0]`. Implementations call
     * `setFoodLevel((manaPercent * 20).roundToInt())` plus
     * `setSaturation(0f)`. Use this only when [suppressHungerHud] is false —
     * once the custom HUD lands suppression is the production path.
     *
     * Fails with [FoodMutationError.InvalidValue] when [manaPercent] is
     * outside `[0, 1]` or non-finite.
     */
    public fun setHungerAsMana(playerId: PlayerId, manaPercent: Float): Result<Unit, FoodMutationError>

    /** Set [playerId]'s raw food level (0..20). Use only for special-case scripts. */
    public fun setFoodLevel(playerId: PlayerId, level: Int): Result<Unit, FoodMutationError>

    /** Set [playerId]'s raw saturation (0..max-foodLevel). */
    public fun setSaturation(playerId: PlayerId, saturation: Float): Result<Unit, FoodMutationError>

    /** Whether [playerId]'s hunger bar is currently suppressed (custom-HUD mode). */
    public fun isHungerSuppressed(playerId: PlayerId): Boolean

    /** Current food level for [playerId], or null if no override has been applied. */
    public fun getFoodLevel(playerId: PlayerId): Int?

    /** Current saturation for [playerId], or null if no override has been applied. */
    public fun getSaturation(playerId: PlayerId): Float?
}

/** Errors returnable from [IPlayerFoodGateway] mutations. */
public sealed interface FoodMutationError {

    /** Numeric argument was non-finite or out of range. */
    public data class InvalidValue(val field: String, val value: Double) : FoodMutationError

    /** Backend rejected the mutation (Paper adapter: player offline). */
    public data class BackendUnavailable(val reason: String) : FoodMutationError
}
