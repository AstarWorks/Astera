package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.match.application.usecase.block.BlockDamageGcUseCase

/** Sweeps sparse block damage every second in worlds with damage (ADR-0297). */
public class BlockDamageGcScheduler(
    private val gateway: IBlockStateGateway,
    private val recovery: BlockDamageGcUseCase,
    private val scheduler: IScheduler,
    private val cyclePeriodTicks: Long = 20L,
) {

    init {
        require(cyclePeriodTicks > 0L) {
            "cyclePeriodTicks must be > 0, was $cyclePeriodTicks"
        }
    }

    /**
     * Arm the cyclic recovery loop. Each cycle: walk every active match
     * world and call [BlockDamageGcUseCase.tick] with the current
     * server tick.
     *
     * The caller owns the returned [IScheduler.TaskHandle] and is
     * responsible for cancelling it on shutdown. The implementation reads
     * [gateway] fresh on every tick so concurrent edits to the world
     * list (matches starting / ending mid-cycle) are observed without a
     * restart.
     */
    public fun start(): IScheduler.TaskHandle =
        scheduler.repeat(ExecutionScope.Global,
            initialDelay = ticks(cyclePeriodTicks),
            period = ticks(cyclePeriodTicks),
        ) {
            val now = scheduler.currentTick()
            for (world in gateway.damagedWorlds()) {
                recovery.tick(world, now)
            }
        }
}
