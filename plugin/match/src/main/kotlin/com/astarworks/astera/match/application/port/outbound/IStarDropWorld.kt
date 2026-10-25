package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * A star drop lying in the world: a pickable item (Paper: a glowing, named, unmergeable NETHER_STAR item entity).
 * A drop is addressed by an opaque handle. Collecting is the host's ordinary item pickup (the player's pickup
 * range, the pickup delay, the pickup animation): the host reports the attempt through [onPickupAttempt] and keeps
 * the item out of every inventory (the star count is the in-memory match store, not an item stack, so nothing can
 * drift, be dropped with Q, or be moved to an ender chest). Crediting stars and [take]-ing the thing is the caller's job.
 */
public interface IStarDropWorld {
    /**
     * Spawns a drop worth [amount] stars; [velocity] (blocks/tick) scatters it; nobody can pick it up for
     * [pickupDelayTicks]. Returns its handle, or `null` if it could not be placed.
     */
    public fun spawn(world: WorldId, position: Vec3, amount: Int, velocity: Vec3?, pickupDelayTicks: Int): String?

    /** Where the drop lies now, or `null` when it no longer exists (chunk unloaded, killed by a command...). */
    public fun locate(handle: String): Vec3?

    /** The drop now stands for [amount] stars (name and stack count). */
    public fun relabel(handle: String, amount: Int)

    public fun remove(handle: String)

    /** [player] collects the drop [handle]: pickup animation toward them, then the thing is removed. */
    public fun take(handle: String, player: PlayerId)

    /** Pickup sound at [position] (heard by those nearby). */
    public fun playPickup(world: WorldId, position: Vec3)

    /** Registers the callback for "[player] tried to pick up the drop [handle]"; the item never enters the inventory either way. */
    public fun onPickupAttempt(listener: (handle: String, player: PlayerId) -> Unit)
}
