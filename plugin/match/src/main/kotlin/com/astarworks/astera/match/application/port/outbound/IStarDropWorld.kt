package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.application.port.outbound.ExecutionScope

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * The visible half of a star drop: a pickable thing lying in the world (Paper: a glowing, named, unmergeable
 * NETHER_STAR item entity that can never enter an inventory). A drop is addressed by an opaque handle.
 * The host cancels its vanilla pickup and reports the attempt through [onPickupAttempt]; crediting stars and
 * [remove]-ing the thing is the caller's job.
 */
public interface IStarDropWorld {
    /** Spawns a drop worth [amount] stars; [velocity] (blocks/tick) scatters it. Returns its handle, or `null` if it could not be placed. */
    public fun spawn(world: WorldId, position: Vec3, amount: Int, velocity: Vec3?): String?

    /** Ownership of the live object addressed by this opaque handle. */
    public fun executionScope(handle: String): ExecutionScope

    /** Where the drop lies now, or `null` when it no longer exists (chunk unloaded, killed by a command...). */
    public fun locate(handle: String): Vec3?

    /** The drop now stands for [amount] stars (name and stack count). */
    public fun relabel(handle: String, amount: Int)

    public fun remove(handle: String)

    /** Pickup sound at [position] (heard by those nearby). */
    public fun playPickup(world: WorldId, position: Vec3)

    /** Registers the callback for "[player] touched the drop [handle]"; the vanilla pickup is cancelled either way. */
    public fun onPickupAttempt(listener: (handle: String, player: PlayerId) -> Unit)
}
