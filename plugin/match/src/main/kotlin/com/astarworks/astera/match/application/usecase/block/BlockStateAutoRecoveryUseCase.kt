package com.astarworks.astera.match.application.usecase.block

import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.domain.world.BlockState
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Per-cycle logic: iterate tracked blocks in a world and regenerate HP for
 * those whose [BlockState.lastDamagedTick] is more than [coolOffTicks] in
 * the past (Wave 5d).
 *
 * # Semantics (RTM `BlockState.md` §"Pseudo-recovery")
 *
 * A tracked block that hasn't taken damage for [coolOffTicks] starts
 * regaining durability at [regenPerCycle] HP each time [tick] is invoked.
 * Fully restored blocks (`durability == state.maxHp`, Wave 5x per-block
 * max) and broken blocks (`durability == 0`) are skipped — the former have
 * nothing to regen, the latter need an explicit
 * [com.astarworks.astera.core.domain.event.BlockBroken] + restoration flow
 * (handled by the BreakCore listener + post-match `BlockChangeJournal`
 * rewind).
 *
 * The scheduler ([BlockRecoveryScheduler]) drives the cycle period — this
 * use case is stateless and called once per cycle. Splitting the cool-off
 * window from the cycle period lets the scheduler tick infrequently
 * (cheap) while keeping the cool-off precise.
 *
 * # Determinism
 *
 * Iteration order is the gateway's internal `Map` order; tests should not
 * depend on it. Mutation order matters only if [regenPerCycle] is large
 * enough to clear multiple blocks in one cycle — the use case writes each
 * block back independently, so partial progress is visible after each
 * [IBlockStateGateway.track] call.
 *
 * @property gateway block durability storage (per-position state).
 * @property coolOffTicks ticks of no-damage required before regen begins
 *   (default 600 = 30 seconds at 20 TPS).
 * @property regenPerCycle HP regained each time [tick] fires for a block
 *   past its cool-off (default 5).
 */
public class BlockStateAutoRecoveryUseCase(
    private val gateway: IBlockStateGateway,
    private val scheduler: com.astarworks.astera.core.application.port.outbound.IScheduler,
    private val coolOffTicks: Long = 600L,
    private val regenPerCycle: Int = 5,
) {

    init {
        require(coolOffTicks >= 0L) { "coolOffTicks must be >= 0, was $coolOffTicks" }
        require(regenPerCycle > 0) { "regenPerCycle must be > 0, was $regenPerCycle" }
    }

    /**
     * Regenerate eligible blocks in [world]. Returns the count of blocks
     * that received regen this cycle (useful for tests + future metrics).
     *
     * Eligibility: `0 < durability < state.maxHp` (per-block maximum) and
     * `lastDamagedTick + coolOffTicks <= currentTick`.
     */
    public fun tick(world: WorldId, currentTick: Long): Int {
        var regenerated = 0
        // Snapshot the tracked map up front — the gateway returns an immutable
        // copy already, but being explicit guards against future impls that
        // return a live view.
        val snapshot = gateway.tracked(world)
        for ((pos, state) in snapshot) {
            if (state.durability >= state.maxHp) continue
            if (state.durability <= 0) continue
            // Cover never heals mid-match; it is rebuilt at match end (ADR-0280).
            if (gateway.isCover(world, pos)) continue
            if (state.lastDamagedTick + coolOffTicks > currentTick) continue
            val nextDurability = (state.durability + regenPerCycle)
                .coerceAtMost(state.maxHp)
            scheduler.run(regionScope(world, pos), kotlin.time.Duration.ZERO) { gateway.track(world, pos, state.copy(durability = nextDurability)) }
            regenerated++
        }
        return regenerated
    }
}
