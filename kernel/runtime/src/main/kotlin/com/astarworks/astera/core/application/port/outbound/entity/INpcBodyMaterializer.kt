package com.astarworks.astera.core.application.port.outbound.entity

import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: gives a logical NPC (an [EntityHandle] minted by
 * [IEntitySpawner], whose clicks are bound in [INpcInteractionRouter]) a
 * physical, clickable body in the running game.
 *
 * Paper registers an implementation (a non-AI villager tagged with the handle
 * plus the click listener that dispatches the bound intent). Hosts without a
 * world, such as the headless simulator, register none: the logical NPC and
 * its intent binding still exist, only the body is skipped.
 */
public interface INpcBodyMaterializer {

    /** Spawn the body for [handle] at [position]; [displayName] is shown as its name tag. */
    public fun materialize(
        handle: EntityHandle,
        world: WorldId,
        position: Vec3,
        yaw: Float,
        pitch: Float,
        displayName: MessageKey,
    )

    /** Remove the body of one [handle] (a per-match NPC despawning); no-op for hosts without bodies. */
    public fun remove(handle: EntityHandle) {}

    /** Remove every body this materializer spawned (plugin disable). */
    public fun removeAll()
}
