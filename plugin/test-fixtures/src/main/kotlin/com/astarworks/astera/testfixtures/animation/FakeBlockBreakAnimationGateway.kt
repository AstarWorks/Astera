package com.astarworks.astera.testfixtures.animation

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.block.IBlockBreakAnimationGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.block.BlockBreakAnimationError
import com.astarworks.astera.core.domain.animation.block.BlockBreakStage
import com.astarworks.astera.core.domain.event.BlockBreakStageChanged
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IBlockBreakAnimationGateway] for use-case tests (Wave 5animation, ADR-0113).
 *
 * Mirrors [com.astarworks.astera.core.application.service.animation.InMemoryBlockBreakAnimationGateway]
 * but exposes an [operations] history so tests can assert ordering.
 *
 * Calling `setBreakStage` with the same stage twice in a row registers
 * only the first call in [operations] (matches the InMemory impl's
 * dedup contract).
 */
public class FakeBlockBreakAnimationGateway(
    private val eventBus: IDomainEventBus? = null,
    private val tickClock: () -> Long = { 0L },
) : IBlockBreakAnimationGateway {

    /** Operation history. */
    public sealed interface BreakAnimOp {
        public data class StageSet(
            val world: WorldId,
            val position: Vec3,
            val previousStage: BlockBreakStage?,
            val newStage: BlockBreakStage,
            val viewers: Set<PlayerId>?,
        ) : BreakAnimOp

        public data class Cancelled(
            val world: WorldId,
            val position: Vec3,
            val previousStage: BlockBreakStage?,
        ) : BreakAnimOp
    }

    private data class Key(val world: WorldId, val position: Vec3)

    private val stages: ConcurrentHashMap<Key, BlockBreakStage> = ConcurrentHashMap()
    private val ops: MutableList<BreakAnimOp> = mutableListOf()

    /** Ordered call sequence. */
    public val operations: List<BreakAnimOp> get() = ops.toList()

    override fun setBreakStage(
        world: WorldId,
        position: Vec3,
        stage: BlockBreakStage,
        broadcastViewers: Set<PlayerId>?,
    ): Result<Unit, BlockBreakAnimationError> {
        val key = Key(world, position)
        val previous = stages[key]
        if (previous == stage) {
            return Result.success(Unit)
        }
        if (stage == BlockBreakStage.RESET) {
            stages.remove(key)
        } else {
            stages[key] = stage
        }
        ops += BreakAnimOp.StageSet(world, position, previous, stage, broadcastViewers)
        eventBus?.publish(
            BlockBreakStageChanged(
                world = world,
                position = position,
                previousStage = previous,
                newStage = stage,
                viewers = broadcastViewers,
                atTick = tickClock(),
            ),
        )
        return Result.success(Unit)
    }

    override fun cancelBreakAnimation(
        world: WorldId,
        position: Vec3,
    ): Result<Unit, BlockBreakAnimationError> {
        val key = Key(world, position)
        val previous = stages.remove(key) ?: return Result.success(Unit)
        ops += BreakAnimOp.Cancelled(world, position, previous)
        eventBus?.publish(
            BlockBreakStageChanged(
                world = world,
                position = position,
                previousStage = previous,
                newStage = BlockBreakStage.RESET,
                viewers = null,
                atTick = tickClock(),
            ),
        )
        return Result.success(Unit)
    }

    override fun getCurrentStage(
        world: WorldId,
        position: Vec3,
    ): BlockBreakStage? = stages[Key(world, position)]

    /** Test cleanup. */
    public fun clear() {
        stages.clear()
        ops.clear()
    }
}
