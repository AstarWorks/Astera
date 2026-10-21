package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Cross-plugin Fake for [IPlayerPoseLookup] (ADR-0174). Poses are scripted via
 * [set]; unknown players resolve to `null`.
 */
public class FakePlayerPoseLookup : IPlayerPoseLookup {

    private val poses: MutableMap<PlayerId, PlayerPose> = mutableMapOf()

    public fun set(playerId: PlayerId, pose: PlayerPose) {
        poses[playerId] = pose
    }

    /** Convenience: standing pose in [world] at [position] looking along [direction]. */
    public fun place(
        playerId: PlayerId,
        position: Vec3,
        direction: Vec3 = Vec3(1.0, 0.0, 0.0),
        world: WorldId = WorldId("world"),
    ) {
        poses[playerId] = PlayerPose.standing(world, position, direction)
    }

    public fun remove(playerId: PlayerId) {
        poses.remove(playerId)
    }

    override fun poseOf(playerId: PlayerId): PlayerPose? = poses[playerId]
}
