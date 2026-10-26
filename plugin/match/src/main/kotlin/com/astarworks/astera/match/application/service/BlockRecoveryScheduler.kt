package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.usecase.block.BlockStateAutoRecoveryUseCase

/**
 * Drives block durability pseudo-recovery on a low-frequency repeating
 * schedule (Wave 5d).
 *
 * The scheduler walks the active [IMatchWorldRepository.all] list once per
 * cycle and calls [BlockStateAutoRecoveryUseCase.tick] for each world. The
 * use case owns the eligibility + regen math; the scheduler exists so
 * wiring stays in one place and so tests can drive the loop with
 * `FakeScheduler.advanceTicks(...)` instead of constructing the use case
 * graph by hand.
 *
 * # Cycle period vs cool-off window
 *
 * The default cycle is **200 ticks (10 s)** while the use case's default
 * cool-off is **600 ticks (30 s)**. Cool-off precision is bounded by the
 * cycle period: a block damaged at tick 0 might not regen until tick 800
 * (cool-off elapses at tick 600, next cycle fires at tick 800). This is
 * acceptable for PoC; tighten the cycle if cool-off precision becomes a
 * gameplay concern.
 *
 * # Lifecycle
 *
 * - [start] arms the repeating loop and returns its [IScheduler.TaskHandle].
 * - Callers (Phase C / tests) cancel via the handle when shutting down a
 *   server / leaving a test.
 *
 * # Threading
 *
 * Production: invoked from the server main thread (Paper scheduler).
 * Tests: invoked synchronously from `FakeScheduler.advanceTicks`. Both run
 * on a single thread.
 *
 * @property matchWorlds source of active match → world bindings.
 * @property recovery per-world regen logic.
 * @property scheduler time + task scheduling port.
 * @property cyclePeriodTicks ticks between recovery cycles (default 200 = 10 s).
 */
public class BlockRecoveryScheduler(
    private val matchWorlds: IMatchWorldRepository,
    private val recovery: BlockStateAutoRecoveryUseCase,
    private val scheduler: IScheduler,
    private val cyclePeriodTicks: Long = 200L,
) {

    init {
        require(cyclePeriodTicks > 0L) {
            "cyclePeriodTicks must be > 0, was $cyclePeriodTicks"
        }
    }

    /**
     * Arm the cyclic recovery loop. Each cycle: walk every active match
     * world and call [BlockStateAutoRecoveryUseCase.tick] with the current
     * server tick.
     *
     * The caller owns the returned [IScheduler.TaskHandle] and is
     * responsible for cancelling it on shutdown. The implementation reads
     * [matchWorlds] fresh on every tick so concurrent edits to the world
     * list (matches starting / ending mid-cycle) are observed without a
     * restart.
     */
    public fun start(): IScheduler.TaskHandle =
        scheduler.repeat(ExecutionScope.Global,
            initialDelay = ticks(cyclePeriodTicks),
            period = ticks(cyclePeriodTicks),
        ) {
            val now = scheduler.currentTick()
            for (matchWorld in matchWorlds.all()) {
                recovery.tick(matchWorld.worldId, now)
            }
        }
}
