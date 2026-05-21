package com.astarworks.astera.combat.testfixtures.aoe

import com.astarworks.astera.combat.application.port.outbound.aoe.ILightningGateway
import com.astarworks.astera.combat.domain.aoe.LightningError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Recording fake for [ILightningGateway] (Wave 5m / ADR-0088).
 *
 * Records every [strike] call so tests can assert on world / position /
 * `damageEntities` flag and call order.
 */
public class FakeLightningGateway : ILightningGateway {

    public data class Record(
        public val world: WorldId,
        public val position: Vec3,
        public val damageEntities: Boolean,
    )

    public val strikes: MutableList<Record> = mutableListOf()
    private var nextOutcome: Result<Unit, LightningError> = Result.success(Unit)

    public fun stubOutcome(outcome: Result<Unit, LightningError>) {
        nextOutcome = outcome
    }

    override fun strike(
        world: WorldId,
        position: Vec3,
        damageEntities: Boolean,
    ): Result<Unit, LightningError> {
        strikes += Record(world, position, damageEntities)
        return nextOutcome
    }
}
