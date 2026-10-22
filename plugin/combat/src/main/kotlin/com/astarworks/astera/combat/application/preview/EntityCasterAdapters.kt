package com.astarworks.astera.combat.application.preview

import com.astarworks.astera.combat.application.port.outbound.IEntityCasterBody
import com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Pose of a non-player caster (ADR-0174 widened): players resolve through [players] first, any other id
 * through [entities]. Lets skills cast by a preview dummy / mob read the same pose a player would give.
 */
public class EntityFallbackPoseLookup(
    private val players: IPlayerPoseLookup,
    private val entities: IEntityCasterBody,
) : IPlayerPoseLookup {
    override fun poseOf(playerId: PlayerId): PlayerPose? = players.poseOf(playerId) ?: entities.poseOf(playerId)
}

/**
 * [IWorldGateway] whose `teleport` also moves non-player casters (`dash_strike` / `teleport` mechanics of a
 * dummy caster); every other member is the wrapped gateway's.
 */
public class EntityTeleportingWorldGateway(
    private val delegate: IWorldGateway,
    private val entities: IEntityCasterBody,
) : IWorldGateway by delegate {
    override fun teleport(playerId: PlayerId, at: Vec3, world: WorldId?, yaw: Float?, pitch: Float?): Boolean =
        delegate.teleport(playerId, at, world, yaw, pitch) || entities.teleport(playerId, at, world, yaw, pitch)
}
