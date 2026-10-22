package com.astarworks.astera.core.application.port.outbound.lobby

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.lobby.LobbyTrainingPlan
import com.astarworks.astera.core.domain.world.WorldId

/**
 * The lobby training range (F3-11, ADR-0200): the region, spawn, dummy and rack-slot landmarks of the
 * training island, published by Core on backends that host the lobby. Combat spawns the dummies and
 * rack from [plan]; the damage rules ask [contains] to keep players in the range immune to each other.
 *
 * Present in the registry only when the lobby content loaded and the backend hosts the lobby.
 */
public interface ILobbyTrainingRange {
    /** The world the range lives in (the lobby world). */
    public val world: WorldId

    public val plan: LobbyTrainingPlan

    /** True when [at] (in [inWorld]; `null` = any world) is inside the range region. */
    public fun contains(inWorld: WorldId?, at: Vec3): Boolean =
        (inWorld == null || inWorld == world) && plan.region.contains(at)
}

/** Plain [ILobbyTrainingRange] over a computed [plan]. */
public class LobbyTrainingRange(
    override val world: WorldId,
    override val plan: LobbyTrainingPlan,
) : ILobbyTrainingRange
