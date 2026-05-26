package com.astarworks.astera.core.application.service.animation

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
 * Default in-memory [IBlockBreakAnimationGateway] (Wave 5animation, ADR-0113).
 *
 * Wired by `AsteraCorePlugin` at priority 0 — Wave 6 lands
 * `PaperBlockBreakAnimationGateway` over `BlockBreakAnimationPacket`
 * at higher priority and shadows this impl.
 *
 * # Dedup behaviour
 *
 * `setBreakStage` is called per-tick during active mining; without
 * dedup the bus would see ~20 [BlockBreakStageChanged] events per
 * second per active miner. The gateway compares the requested stage to
 * the currently-tracked stage and silently no-ops on equality (returns
 * Success without publishing).
 *
 * # Storage shape
 *
 * Tracked per-(world, position) — a single block can only have one
 * active break stage at a time (vanilla constraint). Per-viewer routing
 * is the Paper adapter's concern; the in-memory impl records the viewer
 * subset in the published event but does not partition state.
 */
public class InMemoryBlockBreakAnimationGateway(
    private val eventBus: IDomainEventBus,
    private val tickClock: () -> Long = { 0L },
) : IBlockBreakAnimationGateway {

    private data class Key(val world: WorldId, val position: Vec3)

    private val stages: ConcurrentHashMap<Key, BlockBreakStage> = ConcurrentHashMap()

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
        eventBus.publish(
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
        eventBus.publish(
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
}
