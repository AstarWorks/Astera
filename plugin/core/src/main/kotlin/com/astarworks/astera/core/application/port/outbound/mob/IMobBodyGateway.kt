package com.astarworks.astera.core.application.port.outbound.mob

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.mob.MobError
import com.astarworks.astera.core.domain.mob.MobSpec
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID

/**
 * Physical side of a mob (ADR-0249): turns a [MobSpec] into a real, ticking entity on the
 * host and removes it again. `MinimalMobAdapter` keeps the bookkeeping and the routing; this
 * port is the only thing that knows there is a server underneath, so the adapter stays
 * Bukkit-free.
 *
 * The returned [UUID] is the host entity's own id, so combat (`PlayerId(entityUuid)`) and the
 * `MobKilled` event address the same thing the adapter's `EntityHandle` does.
 *
 * Headless hosts (game-sim, content tools) register no body gateway and the adapter falls
 * back to pure bookkeeping.
 */
public interface IMobBodyGateway {

    /**
     * Spawns the body of [spec] at [position] in [world]: Astera HP (`mob.max_hp`), melee
     * damage (`mob.damage`), speed (`mob.speed`), name, aggression towards players, no
     * vanilla drops. Main thread only. Fails with [MobError.BackendError] instead of throwing.
     */
    public fun spawn(world: WorldId, position: Vec3, spec: MobSpec): Result<UUID, MobError>

    /** Removes the body. Unknown / already dead ids are ignored. */
    public fun remove(id: UUID)

    /** `true` while the body exists, is valid and has hit points left. */
    public fun isAlive(id: UUID): Boolean
}
