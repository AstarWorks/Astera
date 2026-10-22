package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: write a player's food bar (Wave 5k, ADR-0080; F2-2, ADR-0194).
 *
 * # What the food bar is
 *
 * Astera has no eating loop. The vanilla food bar is the **energy bar**: the
 * food level (0..20) mirrors `energy / max energy` (`EnergyFoodBar`, domain).
 * Vanilla hunger must have no effect of its own, so the Paper adapter cancels
 * every `FoodLevelChangeEvent` (a plain `Player.setFoodLevel` fires none, so
 * Astera's own writes pass), natural health regeneration from satiety and
 * starvation damage. HP is the Astera container (ADR-0149).
 */
public interface IPlayerFoodGateway {

    /**
     * Write the energy mirror: food level [level] (0..20), saturation 0 and
     * exhaustion 0, so nothing but Astera moves the bar and satiety never
     * heals the player.
     *
     * Fails with [FoodMutationError.InvalidValue] when [level] is outside 0..20.
     */
    public fun mirrorEnergy(playerId: PlayerId, level: Int): Result<Unit, FoodMutationError>

    /** Set [playerId]'s raw food level (0..20). Use only for special-case scripts. */
    public fun setFoodLevel(playerId: PlayerId, level: Int): Result<Unit, FoodMutationError>

    /** Set [playerId]'s raw saturation (0..max-foodLevel). */
    public fun setSaturation(playerId: PlayerId, saturation: Float): Result<Unit, FoodMutationError>

    /** Current food level for [playerId], or null if never written / offline. */
    public fun getFoodLevel(playerId: PlayerId): Int?

    /** Current saturation for [playerId], or null if never written / offline. */
    public fun getSaturation(playerId: PlayerId): Float?
}

/** Errors returnable from [IPlayerFoodGateway] mutations. */
public sealed interface FoodMutationError {

    /** Numeric argument was non-finite or out of range. */
    public data class InvalidValue(val field: String, val value: Double) : FoodMutationError

    /** Backend rejected the mutation (Paper adapter: player offline). */
    public data class BackendUnavailable(val reason: String) : FoodMutationError
}
