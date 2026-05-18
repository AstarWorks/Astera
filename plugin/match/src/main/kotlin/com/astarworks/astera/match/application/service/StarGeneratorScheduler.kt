package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.application.usecase.generator.AdvanceGeneratorChargeUseCase
import com.astarworks.astera.match.application.usecase.generator.SpawnResourceUseCase
import kotlin.time.Duration

/**
 * Drives Star Generator energy on a 1-tick repeating schedule (Wave 5c).
 *
 * The scheduler itself does very little: every tick it walks the active match
 * list and calls [AdvanceGeneratorChargeUseCase.advance] with `deltaTicks = 1`.
 * The use case owns the threshold / spawn / event-publish logic; the scheduler
 * exists so wiring stays in one place and so tests can drive the loop with
 * `FakeScheduler.advanceTicks(...)` instead of constructing the use case
 * graph by hand.
 *
 * [spawn] is held here even though it is not invoked directly from this class:
 * the constructor signature matches the wave-5c handoff so wiring stays
 * uniform when Phase C registers the bundle on the `ServiceRegistry`. The
 * use case the scheduler *does* invoke
 * ([AdvanceGeneratorChargeUseCase]) holds its own reference to [SpawnResourceUseCase].
 *
 * The [eventBus] reference is similarly held for parity with the handoff
 * doc; the use case publishes events through its own injected bus.
 *
 * # Lifecycle
 *
 * - [start] arms the 1-tick `runRepeating` loop and returns its [IScheduler.TaskHandle].
 * - Callers (Phase C / tests) cancel via the handle when shutting down a
 *   server / leaving a test.
 *
 * # Threading
 *
 * Production: invoked from the server main thread (Paper scheduler).
 * Tests: invoked synchronously from `FakeScheduler.advanceTicks`. Both run
 * on a single thread.
 */
public class StarGeneratorScheduler(
    private val generators: IGeneratorRepository,
    private val advance: AdvanceGeneratorChargeUseCase,
    @Suppress("unused") private val spawn: SpawnResourceUseCase,
    private val scheduler: IScheduler,
    @Suppress("unused") private val eventBus: IDomainEventBus,
    private val matches: IMatchRepository,
) {

    /**
     * Arm the per-tick generator loop. Each tick: for every active match,
     * advance every one of its generators by 1 tick.
     *
     * The caller owns the returned [IScheduler.TaskHandle] and is
     * responsible for cancelling it on shutdown. The implementation reads
     * [matches] / [generators] fresh on every tick so concurrent edits to
     * the match list (add / remove during a match) are observed without a
     * restart.
     */
    public fun start(): IScheduler.TaskHandle =
        scheduler.runRepeating(
            initialDelay = Duration.ZERO,
            period = ticks(1L),
        ) {
            for (match in matches.findActiveMatches()) {
                // Skip matches that have no generators — keeps the per-tick
                // cost proportional to the number of generators, not matches.
                if (generators.listForMatch(match.id).isEmpty()) continue
                advance.advance(match.id, deltaTicks = 1L)
            }
        }
}
