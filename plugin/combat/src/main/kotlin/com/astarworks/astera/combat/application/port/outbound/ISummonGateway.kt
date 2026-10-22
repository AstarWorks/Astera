package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Host-neutral mob spawning for `Mechanic.Summon` (WP5). The spawned mob
 * travels through the targeting pipeline as `PlayerId(entityUuid)` like every
 * non-player entity. Lifetime, ownership and "do not attack the owner's side"
 * are tracked by `SummonRegistry`; the gateway only creates and removes bodies.
 */
public interface ISummonGateway {

    /**
     * Spawns the mob described by [request]; returns its id, or `null` when the
     * entity type is unknown / unsupported or the world is not loaded.
     */
    public fun spawn(request: SummonRequest): PlayerId?

    /** Removes a previously spawned summon; unknown / already dead ids are ignored. */
    public fun despawn(id: PlayerId)

    /** @property entityTypeKey vendor-neutral entity type (`ZOMBIE`, `minecraft:skeleton`, ...). */
    public data class SummonRequest(
        val entityTypeKey: String,
        val world: WorldId,
        val position: Vec3,
        val owner: PlayerId,
        val team: TeamId?,
    )
}
