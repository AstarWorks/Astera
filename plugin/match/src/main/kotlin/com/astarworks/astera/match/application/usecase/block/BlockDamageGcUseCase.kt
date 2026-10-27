package com.astarworks.astera.match.application.usecase.block

import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.block.IBlockBreakAnimationGateway
import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.core.domain.world.WorldId

/** ADR-0297: idle damage is forgotten in batches; no healing or full-HP records. */
public class BlockDamageGcUseCase(
    private val gateway: IBlockStateGateway,
    private val scheduler: IScheduler,
    private val animation: () -> IBlockBreakAnimationGateway? = { null },
) {
    public fun tick(world: WorldId, currentTick: Long): Int {
        val expired = gateway.expireDamage(world, currentTick)
        expired.forEach { pos ->
            scheduler.run(regionScope(world, pos), kotlin.time.Duration.ZERO) {
                // A hit after the sweep owns the current overlay.
                if (!gateway.hasDamage(world, pos)) animation()?.cancelBreakAnimation(world, pos)
            }
        }
        return expired.size
    }
}
