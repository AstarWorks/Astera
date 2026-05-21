package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IPlayerLocationQueryService
import com.astarworks.astera.core.application.service.spatial.InMemoryPlayerLocationQueryService
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Cross-plugin Fake for [IPlayerLocationQueryService]. Wraps the in-memory
 * impl and records every position update for assertions.
 *
 * Wave 5n (ADR-0080).
 */
public class FakePlayerLocationQueryService : IPlayerLocationQueryService {

    public data class PositionUpdate(val playerId: PlayerId, val world: WorldId, val position: Vec3)

    private val delegate: InMemoryPlayerLocationQueryService = InMemoryPlayerLocationQueryService()

    public val updates: MutableList<PositionUpdate> = mutableListOf()
    public val forgottenPlayers: MutableList<PlayerId> = mutableListOf()

    override fun playersIn(world: WorldId, region: Region): Collection<PlayerId> =
        delegate.playersIn(world, region)

    override fun playersWithinDistance(
        world: WorldId,
        center: Vec3,
        radius: Double,
    ): Collection<PlayerId> = delegate.playersWithinDistance(world, center, radius)

    override fun nearestPlayer(world: WorldId, center: Vec3, maxDistance: Double): PlayerId? =
        delegate.nearestPlayer(world, center, maxDistance)

    override fun updatePlayerPosition(playerId: PlayerId, world: WorldId, position: Vec3) {
        updates += PositionUpdate(playerId, world, position)
        delegate.updatePlayerPosition(playerId, world, position)
    }

    override fun forgetPlayer(playerId: PlayerId) {
        forgottenPlayers += playerId
        delegate.forgetPlayer(playerId)
    }
}
