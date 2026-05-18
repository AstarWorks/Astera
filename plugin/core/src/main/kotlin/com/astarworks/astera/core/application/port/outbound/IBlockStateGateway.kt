package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.BlockState
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: per-position block durability state (Wave 5d).
 *
 * Backs `BlockStateAutoRecoveryUseCase` (pseudo-recovery scheduler) and
 * `RestoreBlocksOnMatchEndedUseCase` (post-match revert). Phase 1 PoC uses
 * `InMemoryBlockStateGateway`; the Paper-side `PaperBlockStateGateway`
 * (Bukkit `BlockData` + NBT durability) lands in Wave 6.
 *
 * Untracked positions return [BlockState.MAX_DURABILITY] from
 * [getDurability]. [applyDamage] tracks the position if it wasn't already
 * tracked, then deducts the damage from current durability (or starts at
 * MAX_DURABILITY if previously untracked). When durability falls to 0 the
 * adapter is expected to publish a `BlockBroken` domain event (which the
 * BreakCore listener and the BlockChangeJournal both subscribe to).
 */
public interface IBlockStateGateway {

    /** Current durability at [pos] in [world]; defaults to MAX for untracked positions. */
    public fun getDurability(world: WorldId, pos: Vec3): Int

    /**
     * Apply [amount] damage to the block at [pos], modulated by the [tool]
     * tier (formula in `BlockDurabilityRule`). Returns the resulting
     * [BlockState] (durability clamped to 0..MAX_DURABILITY).
     */
    public fun applyDamage(
        world: WorldId,
        pos: Vec3,
        amount: Int,
        tool: ToolType,
    ): BlockState

    /** Restore [pos] to MAX_DURABILITY (used by post-match restoration + pseudo-recovery). */
    public fun restore(world: WorldId, pos: Vec3)

    /** Begin tracking [pos] with the supplied initial state (e.g. for placed blocks). */
    public fun track(world: WorldId, pos: Vec3, state: BlockState)

    /**
     * Snapshot of every tracked position in [world] (used by
     * `BlockRecoveryScheduler` to iterate damaged blocks for periodic regen).
     */
    public fun tracked(world: WorldId): Map<Vec3, BlockState>
}
