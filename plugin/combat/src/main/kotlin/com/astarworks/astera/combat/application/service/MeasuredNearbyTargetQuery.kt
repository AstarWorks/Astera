package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.port.outbound.NearbyTarget
import com.astarworks.astera.core.application.service.observability.ExecutionCosts
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/** Covers archetype/dash queries as well as declarative targeters, without reordering results. */
public class MeasuredNearbyTargetQuery(private val delegate: INearbyTargetQuery) : INearbyTargetQuery {
    override fun findInRadius(center: Vec3, radius: Double, world: WorldId?): List<PlayerId> =
        ExecutionCosts.measure("target.radius", "all") { delegate.findInRadius(center, radius, world) }
    override fun findViewers(center: Vec3, radius: Double, world: WorldId?): List<PlayerId> =
        ExecutionCosts.measure("target.viewers", "all") { delegate.findViewers(center, radius, world) }
    override fun findNearby(center: Vec3, radius: Double, world: WorldId?): List<NearbyTarget> =
        ExecutionCosts.measure("target.nearby", "all") { delegate.findNearby(center, radius, world) }
}
