package com.astarworks.astera.combat.testfixtures

import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Test fixture: returns a pre-seeded list of [PlayerId]s regardless of the
 * (center, radius, world) tuple. Sufficient for Wave 5a SkillCastDispatcher
 * tests that only need to assert "the handler iterated my candidates".
 *
 * Calls are recorded in [calls] for assertion on the (center, radius, world)
 * arguments when those matter.
 */
public class FakeNearbyTargetQuery(
    public val players: List<PlayerId> = emptyList(),
) : INearbyTargetQuery {

    public data class Call(val center: Vec3, val radius: Double, val world: WorldId?)

    public val calls: MutableList<Call> = mutableListOf()

    override fun findInRadius(center: Vec3, radius: Double, world: WorldId?): List<PlayerId> {
        calls += Call(center, radius, world)
        return players
    }
}
