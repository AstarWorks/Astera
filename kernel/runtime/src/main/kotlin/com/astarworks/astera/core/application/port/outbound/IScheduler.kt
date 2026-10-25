package com.astarworks.astera.core.application.port.outbound

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Explicit ownership for every callback and coroutine (ADR-0252). */
public interface IScheduler {
    /**
     * Monotonic global tick counter, advanced by a GlobalRegionScheduler one-tick timer.
     * This measures global ticks since scheduler startup, not any entity/region's local tick.
     * Io delays use wall time; entity and region delays use their owner's local ticks.
     */
    public fun currentTick(): Long
    public fun run(scope: ExecutionScope, delay: Duration, block: () -> Unit): TaskHandle
    public fun repeat(scope: ExecutionScope, initialDelay: Duration, period: Duration, block: () -> Unit): TaskHandle
    public fun dispatcher(scope: ExecutionScope): CoroutineDispatcher

    public interface TaskHandle {
        public fun cancel()
        public val isCancelled: Boolean
        /** Called once if the owner retires; late registrations are called immediately. Never called for cancel(). */
        public fun onRetired(cb: () -> Unit)
    }
}

/** Minecraft tick duration. */
public val TICK: Duration = 50.milliseconds

public fun ticks(count: Long): Duration = (count * 50L).milliseconds

public fun IScheduler.runLaterTicks(scope: ExecutionScope, count: Long, block: () -> Unit): IScheduler.TaskHandle =
    run(scope, ticks(count), block)

public fun IScheduler.runRepeatingTicks(
    scope: ExecutionScope,
    initialDelayTicks: Long,
    periodTicks: Long,
    block: () -> Unit,
): IScheduler.TaskHandle = repeat(scope, ticks(initialDelayTicks), ticks(periodTicks), block)

/** Dispatch completion work to the declared owner, including already-completed futures. */
public fun IScheduler.executor(scope: ExecutionScope): java.util.concurrent.Executor =
    java.util.concurrent.Executor { command -> run(scope, Duration.ZERO) { command.run() } }
