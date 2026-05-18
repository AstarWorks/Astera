package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.match.application.port.outbound.IBarrierGateway

/**
 * Per-tick driver for barrier lifecycle (Wave 5d).
 *
 * # What it does today (Phase 1 PoC)
 *
 * Heartbeat. Every [periodTicks] in-game ticks the scheduler walks
 * [IBarrierGateway.all] and does nothing with the result. The iteration
 * exists so that subsequent waves can attach behaviour (sabotage-skill
 * timers, anomaly-triggered `Active` → `Disabled` transitions, expiry of
 * temporary barriers) without rewiring `AsteraMatchPlugin` or threading a
 * new scheduler handle into the platform layer.
 *
 * # Why ship a no-op scheduler now?
 *
 * The Wave 5d handoff explicitly budgets the entry point so that follow-up
 * use cases land as a *use-case addition* rather than a *wiring change*.
 * Cross-wave wiring churn is the single largest source of merge conflicts
 * in the multi-plugin layout (ADR-0022); a one-shot scheduler skeleton
 * costs ~20 lines now and saves a wiring round trip later.
 *
 * # Lifecycle
 *
 * - [start] arms the repeating task and returns its
 *   [IScheduler.TaskHandle].
 * - Callers (Phase C `AsteraMatchPlugin.onDisable`, tests) cancel via the
 *   handle when shutting down.
 *
 * # Threading
 *
 * Production: server main thread (Paper scheduler).
 * Tests: [com.astarworks.astera.testfixtures.FakeScheduler.advanceTicks]
 * fires the block synchronously.
 *
 * @property periodTicks how often (in in-game ticks) the heartbeat fires.
 *   Default `20L` (1 second) matches the cadence of `StarGeneratorScheduler`'s
 *   coarse-grained companions. Per-tick precision is unnecessary while the
 *   body is a no-op; finer resolution is cheap to add when behaviour lands.
 */
public class BarrierStatusScheduler(
    private val gateway: IBarrierGateway,
    private val scheduler: IScheduler,
    private val periodTicks: Long = DEFAULT_PERIOD_TICKS,
) {

    /**
     * Arm the heartbeat loop. Caller owns the returned
     * [IScheduler.TaskHandle] and is responsible for cancelling it on
     * shutdown. The implementation reads [gateway] fresh on every tick so
     * concurrent edits (spawn / despawn during a match) are observed
     * without a restart.
     */
    public fun start(): IScheduler.TaskHandle =
        scheduler.runRepeating(
            initialDelay = ticks(periodTicks),
            period = ticks(periodTicks),
        ) {
            // Iterate barriers so future per-tick behaviour can land as a
            // use-case addition (Wave 6+). Intentionally side-effect free
            // in Wave 5d — see the class kdoc for the rationale.
            gateway.all()
        }

    public companion object {
        /** Default heartbeat cadence (1 second at 20 TPS). */
        public const val DEFAULT_PERIOD_TICKS: Long = 20L
    }
}
