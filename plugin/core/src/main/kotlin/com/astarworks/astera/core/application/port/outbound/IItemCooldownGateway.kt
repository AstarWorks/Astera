package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: the native item cooldown overlay (F2-2, ADR-0194).
 *
 * Minecraft draws a "sweep" over an item slot while that item's cooldown runs.
 * Astera uses it as the reload progress indicator in place of the removed boss
 * bar: the pistol visibly refills while the reload timer runs.
 */
public interface IItemCooldownGateway {

    /**
     * Start the cooldown sweep of [ticks] on the item [playerId] is holding.
     *
     * Fails with [ItemCooldownError.InvalidTicks] when [ticks] is below 1 and
     * [ItemCooldownError.BackendUnavailable] when the player is offline.
     */
    public fun showHeldItemCooldown(playerId: PlayerId, ticks: Int): Result<Unit, ItemCooldownError>
}

/** Errors returnable from [IItemCooldownGateway]. */
public sealed interface ItemCooldownError {
    public data class InvalidTicks(val ticks: Int) : ItemCooldownError
    public data class BackendUnavailable(val reason: String) : ItemCooldownError
}
