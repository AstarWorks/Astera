package com.astarworks.astera.combat.application.service.aoe

import com.astarworks.astera.combat.application.port.outbound.aoe.ILightningGateway
import com.astarworks.astera.combat.domain.aoe.LightningError
import com.astarworks.astera.combat.domain.event.aoe.LightningStrike
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Default in-memory binding for [ILightningGateway] (Wave 5m / ADR-0088).
 *
 * Publishes [LightningStrike] for each successful invocation. No actual
 * world side effect — Wave 6a `PaperLightningGateway` overlays at
 * priority 100 with the real `World.strikeLightning` call.
 */
public class InMemoryLightningGateway(
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
) : ILightningGateway {

    override fun strike(
        world: WorldId,
        position: Vec3,
        damageEntities: Boolean,
    ): Result<Unit, LightningError> {
        eventBus.publish(
            LightningStrike(
                world = world,
                position = position,
                damageEntities = damageEntities,
                atTick = scheduler.currentTick(),
            ),
        )
        return Result.success(Unit)
    }
}
