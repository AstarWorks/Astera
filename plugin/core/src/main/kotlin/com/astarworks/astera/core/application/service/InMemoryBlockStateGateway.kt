package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.BlockState
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Default in-memory [IBlockStateGateway] (Wave 5d).
 *
 * Phase 1 PoC: a `Map<WorldId, MutableMap<Vec3, BlockState>>` tracks every
 * damaged or placed position. Untracked positions return
 * [BlockState.MAX_DURABILITY] from [getDurability] — call sites never need to
 * pre-register stage geometry; the first damage event triggers tracking.
 *
 * **Lifecycle of a tracked entry**:
 * - First [applyDamage] on an untracked position seeds a fresh [BlockState]
 *   at MAX_DURABILITY then deducts the damage.
 * - [track] writes the supplied state verbatim — used by placed blocks
 *   ([com.astarworks.astera.core.domain.event.BlockPlaced] listeners) and by
 *   the pseudo-recovery scheduler to bump durability back up.
 * - [restore] resets the tracked entry to MAX_DURABILITY (preserves
 *   `blockType` so pseudo-recovery can keep iterating). Untracked positions
 *   are a no-op.
 *
 * **Event publishing**: this gateway does NOT publish
 * [com.astarworks.astera.core.domain.event.BlockBroken] when durability hits
 * 0. The producer that drives damage (e.g. the BreakCore listener) owns the
 * decision of whether a particular hit "breaks" a block — the gateway just
 * tracks the resulting number. The optional [eventBus] is held for
 * forward-compat with a future variant that emits domain events on internal
 * transitions; right now it is unused.
 *
 * **Not thread-safe** — Astera invariant: all match-state mutation runs on
 * the server main thread (per `IScheduler` regional contract). The plain
 * `MutableMap` is intentional; concurrent collections would imply support we
 * do not provide.
 *
 * **Why `initialTick` is a lambda**: tests inject a `FakeScheduler`-driven
 * value so `lastDamagedTick` is deterministic; production wires
 * `scheduler::currentTick`. Default `{ 0L }` is convenient when no scheduler
 * is on hand (e.g. trivial bootstrap).
 */
public class InMemoryBlockStateGateway(
    @Suppress("unused") private val eventBus: IDomainEventBus? = null,
    private val initialTick: () -> Long = { 0L },
) : IBlockStateGateway {

    private val worlds: MutableMap<WorldId, MutableMap<Vec3, BlockState>> = mutableMapOf()

    override fun getDurability(world: WorldId, pos: Vec3): Int =
        worlds[world]?.get(pos)?.durability ?: BlockState.MAX_DURABILITY

    override fun applyDamage(
        world: WorldId,
        pos: Vec3,
        amount: Int,
        tool: ToolType,
    ): BlockState {
        val store = worlds.getOrPut(world) { mutableMapOf() }
        val existing = store[pos]
        val current = existing?.durability ?: BlockState.MAX_DURABILITY
        // amount may be negative or zero on caller error — clamp damage to >= 0
        // so we never accidentally heal via applyDamage. Healing is restore() / track().
        val safeAmount = amount.coerceAtLeast(0)
        val nextDurability = (current - safeAmount).coerceIn(0, BlockState.MAX_DURABILITY)
        val nextState = (existing ?: defaultStateFor(pos)).copy(
            durability = nextDurability,
            lastDamagedTick = initialTick(),
        )
        store[pos] = nextState
        // tool is currently passed through unchanged — BlockDurabilityRule
        // application happens upstream of the gateway in Wave 5d worker β.
        @Suppress("UNUSED_PARAMETER") tool
        return nextState
    }

    override fun restore(world: WorldId, pos: Vec3) {
        val store = worlds[world] ?: return
        val existing = store[pos] ?: return
        store[pos] = existing.copy(
            durability = BlockState.MAX_DURABILITY,
            // Don't bump lastDamagedTick on restore — the field's only consumer
            // is the cool-off scheduler, which gates on (now - lastDamagedTick).
            // A restored block has no pending recovery work, so the existing
            // tick value is irrelevant; keeping it makes the field meaning
            // ("when did this block last take damage") still hold.
        )
    }

    override fun track(world: WorldId, pos: Vec3, state: BlockState) {
        val store = worlds.getOrPut(world) { mutableMapOf() }
        store[pos] = state
    }

    override fun tracked(world: WorldId): Map<Vec3, BlockState> =
        worlds[world]?.toMap() ?: emptyMap()

    /**
     * Seed a [BlockState] for a position that was previously untracked.
     *
     * Phase 1 PoC: we don't know what the original block was (no world-read
     * port wired yet), so we use a placeholder [BlockType] `astera:unknown`.
     * Wave 6's `PaperBlockStateGateway` will read the actual `BlockData` from
     * the world and supply the real type.
     */
    private fun defaultStateFor(@Suppress("UNUSED_PARAMETER") pos: Vec3): BlockState =
        BlockState(
            blockType = UNKNOWN_BLOCK_TYPE,
            durability = BlockState.MAX_DURABILITY,
            lastDamagedTick = 0L,
            damagedBy = null,
        )

    private companion object {
        private val UNKNOWN_BLOCK_TYPE = BlockType("astera:unknown")
    }
}
