package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Wave 5a default binding of [INearbyTargetQuery] — always returns an empty
 * list. MELEE / AOE skills cast in environments without a Paper runtime
 * (unit tests, balance simulations, the DI graph before Wave 6 wires
 * `PaperNearbyTargetQuery`) deal zero damage, which is the safe default.
 *
 * Wave 6 swaps this out for a `PaperNearbyTargetQuery` backed by
 * `World.getNearbyEntities` filtered to players.
 */
public class EmptyNearbyTargetQuery : INearbyTargetQuery {
    override fun findInRadius(center: Vec3, radius: Double, world: WorldId?): List<PlayerId> =
        emptyList()
}
