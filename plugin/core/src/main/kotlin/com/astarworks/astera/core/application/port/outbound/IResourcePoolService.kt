package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.PlayerResourcePool
import com.astarworks.astera.core.domain.resource.ResourceCap
import com.astarworks.astera.core.domain.resource.ResourceType

/**
 * Outbound port: per-player Ammo / Stamina / Mana state (Wave 5k, ADR-0080).
 *
 * # Annihilation vision
 *
 * Astera = "銃剣魔法 Annihilation server". Each weapon archetype carries one
 * resource type ([ResourceType]); the active weapon's resource is what the
 * HUD renders and what skill casts deplete.
 *
 * # Boundary with [com.astarworks.astera.core.domain.attribute.IAttributeContainer]
 *
 * `IAttributeContainer` (Wave 5e, ADR-0058) carries **abstract numeric
 * attributes** with modifier stacking — things like `max_hp`, `crit_chance`,
 * `armor_pen`. The value is static-ish per match; modifiers stack via the
 * SET/ADD/MULTIPLY pipeline.
 *
 * This service carries **per-tick depletable runtime state** — the counter
 * goes down on use and back up via [IResourceRegenScheduler]. No modifier
 * pipeline: changes happen via [spendResource] / [reload] only. The two
 * layers do not overlap; combat resolves through `IAttributeContainer`,
 * resource accounting is this service.
 *
 * # Init
 *
 * Wave 5e `RestoreInventoryOnPlayerJoinedListener` calls
 * [initialiseFor] when a player joins. Implementations seed all three
 * counters to the per-type max from `content/balance/resource-defaults.yaml`.
 */
public interface IResourcePoolService {

    /**
     * Initialise [playerId]'s pool to per-type maxima. Idempotent — if the
     * player already has a pool, the existing values are preserved (call
     * [setActiveWeapon] to switch what the HUD reads).
     */
    public fun initialiseFor(playerId: PlayerId): PlayerResourcePool

    /** Return the current snapshot for [playerId], or `null` if not initialised. */
    public fun currentPool(playerId: PlayerId): PlayerResourcePool?

    /**
     * Update the active weapon binding for [playerId]. The HUD-layer
     * subscriber re-renders the resource bar against the new archetype's
     * [ResourceType].
     */
    public fun setActiveWeapon(playerId: PlayerId, weapon: ItemId?)

    /**
     * Subtract [amount] of [type] from [playerId]'s pool.
     *
     *   - Success returns the **new** value after subtraction.
     *   - Fails with [ResourceError.Insufficient] when the current value is
     *     less than [amount]; pool is unchanged.
     *   - Fails with [ResourceError.UninitialisedPlayer] when [initialiseFor]
     *     has not been called.
     *   - Fails with [ResourceError.InvalidValue] when [amount] < 0.
     *
     * Publishes [com.astarworks.astera.core.domain.event.ResourceSpent] on
     * success.
     */
    public fun spendResource(
        playerId: PlayerId,
        type: ResourceType,
        amount: Int,
    ): Result<Int, ResourceError>

    /**
     * Refill [playerId]'s Ammo for [weapon] back to its max (per
     * weapon spec or [ResourceCap.DEFAULTS]).
     *
     * Only valid for items whose `resourceType == Ammo` AND whose
     * `resourceReloadable == true`. Other archetypes get
     * [ResourceError.NotReloadable].
     *
     * Publishes [com.astarworks.astera.core.domain.event.ResourceReloaded]
     * on success.
     */
    public fun reload(playerId: PlayerId, weapon: ItemId): Result<Int, ResourceError>

    /**
     * Add [amount] of [type] (regen tick). Clamped at the per-type cap.
     * Returns the new value.
     *
     * Called by [IResourceRegenScheduler]. Fails with
     * [ResourceError.UninitialisedPlayer] when [playerId] has no pool.
     */
    public fun applyRegen(
        playerId: PlayerId,
        type: ResourceType,
        amount: Int,
    ): Result<Int, ResourceError>

    /**
     * Cap + regen rate currently in force for [playerId]'s [type]: the active
     * weapon's override when it uses [type], else the per-type default.
     */
    public fun capFor(playerId: PlayerId, type: ResourceType): ResourceCap
}

/** Errors returnable from [IResourcePoolService]. */
public sealed interface ResourceError {

    /** Player hasn't been initialised; call [IResourcePoolService.initialiseFor] first. */
    public data object UninitialisedPlayer : ResourceError

    /** Spend rejected because pool < amount. [available] is the current value. */
    public data class Insufficient(val type: ResourceType, val available: Int, val requested: Int) : ResourceError

    /** Reload called on a non-reloadable item (Stamina / Mana / no resource type). */
    public data class NotReloadable(val weapon: com.astarworks.astera.core.domain.item.ItemId) : ResourceError

    /** Unknown weapon id (not in registry). */
    public data class UnknownWeapon(val weapon: com.astarworks.astera.core.domain.item.ItemId) : ResourceError

    /** Numeric argument was negative. */
    public data class InvalidValue(val field: String, val value: Int) : ResourceError
}
