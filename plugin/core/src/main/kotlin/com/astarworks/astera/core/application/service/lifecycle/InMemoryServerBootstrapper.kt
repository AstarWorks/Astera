package com.astarworks.astera.core.application.service.lifecycle

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.lifecycle.IServerBootstrapper
import com.astarworks.astera.core.application.port.outbound.lifecycle.IServerLifecycleListener
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.lifecycle.ServerBooted
import com.astarworks.astera.core.domain.event.lifecycle.ServerReady
import com.astarworks.astera.core.domain.event.lifecycle.ServerShutdownCompleted
import com.astarworks.astera.core.domain.event.lifecycle.ServerShutdownStarted
import com.astarworks.astera.core.domain.lifecycle.LifecycleError
import com.astarworks.astera.core.domain.lifecycle.LifecycleStateSnapshot
import com.astarworks.astera.core.domain.lifecycle.ShutdownPhase
import com.astarworks.astera.core.domain.lifecycle.ShutdownReason
import java.time.Instant

/**
 * Default [IServerBootstrapper] implementation (Wave 5w / ADR-0089).
 *
 * Maintains an ordered, priority-sorted listener list and walks
 * [ShutdownPhase.ORDER] on shutdown. Catches every listener exception so a
 * single misbehaving plugin does not prevent the rest of the shutdown from
 * finishing.
 *
 * Pure application-layer logic: no Bukkit / Paper / JVM shutdown hook
 * binding. Wave 6a wraps this with a `PaperServerBootstrapper` that hooks
 * `JavaPlugin.onDisable` + the JVM signal handler.
 */
public class InMemoryServerBootstrapper(
    private val eventBus: IDomainEventBus,
    private val clock: () -> Instant = { Instant.now() },
) : IServerBootstrapper {

    private data class Entry(
        val listener: IServerLifecycleListener,
        val sequence: Long,
        var active: Boolean = true,
    )

    private val entries: MutableList<Entry> = mutableListOf()
    private var sequenceCounter: Long = 0L

    private enum class State { Idle, Booted, Ready }
    private var state: State = State.Idle

    override fun register(listener: IServerLifecycleListener): IServerBootstrapper.Registration {
        val entry = Entry(listener, sequenceCounter++)
        entries += entry
        return object : IServerBootstrapper.Registration {
            override fun unregister() {
                entry.active = false
                entries.remove(entry)
            }
            override val isActive: Boolean get() = entry.active
        }
    }

    override fun listenersInFireOrder(): List<IServerLifecycleListener> =
        entries.filter { it.active }
            .sortedWith(compareByDescending<Entry> { it.listener.priority }.thenBy { it.sequence })
            .map { it.listener }

    override fun boot(): Result<Unit, LifecycleError> {
        if (state != State.Idle) return Result.Failure(LifecycleError.AlreadyBooted)
        val listeners = listenersInFireOrder()
        var count = 0
        for (l in listeners) {
            runListenerSafely { l.onBoot() }
            count++
        }
        state = State.Booted
        eventBus.publish(ServerBooted(pluginsBootedCount = count))
        return Result.Success(Unit)
    }

    override fun markReady(): Result<Unit, LifecycleError> {
        if (state == State.Idle) return Result.Failure(LifecycleError.NotReadyableYet)
        // Idempotent: markReady() after a prior markReady() is a no-op.
        if (state == State.Ready) return Result.Success(Unit)
        val now = clock()
        for (l in listenersInFireOrder()) {
            runListenerSafely { l.onReady() }
        }
        state = State.Ready
        eventBus.publish(ServerReady(readyAt = now))
        return Result.Success(Unit)
    }

    override fun shutdown(reason: ShutdownReason): Result<LifecycleStateSnapshot, LifecycleError> {
        if (state == State.Idle) return Result.Failure(LifecycleError.NotBooted)
        val startedAt = clock()
        eventBus.publish(ServerShutdownStarted(reason = reason))

        val listenersSnapshot = listenersInFireOrder()
        // Overall "onShutdown" notice first (priority order).
        val failures = mutableListOf<LifecycleError.Aggregate.ListenerFailure>()
        for (l in listenersSnapshot) {
            runListenerSafely(
                listener = l,
                onFail = { msg -> failures += LifecycleError.Aggregate.ListenerFailure(l::class.simpleName ?: "?", msg) },
            ) { l.onShutdown(reason) }
        }
        // Then walk each phase in declared order.
        val executed = mutableListOf<ShutdownPhase>()
        for (phase in ShutdownPhase.ORDER) {
            for (l in listenersSnapshot) {
                runListenerSafely(
                    listener = l,
                    onFail = { msg -> failures += LifecycleError.Aggregate.ListenerFailure(l::class.simpleName ?: "?", msg) },
                ) { l.onShutdownPhase(phase, reason) }
            }
            executed += phase
        }

        val completedAt = clock()
        val snapshot = LifecycleStateSnapshot(
            reason = reason,
            startedAt = startedAt,
            completedAt = completedAt,
            phasesExecuted = executed,
            listenerFailures = failures,
        )
        state = State.Idle
        eventBus.publish(ServerShutdownCompleted(snapshot = snapshot))
        return Result.Success(snapshot)
    }

    // ─── internals ─────────────────────────────────────────────────────────

    /**
     * Catches **any** [kotlin.Throwable] from a listener (including
     * RuntimeException and Error subclasses) so a single bad plugin cannot
     * abort the global shutdown. The error is forwarded via [onFail] for
     * aggregation; the next listener still runs.
     *
     * This is the **service layer**, not application/. Application-level
     * APIs return `Result<T, E>`; orchestrators that fan-out to untrusted
     * listener code legitimately need a safety net.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun runListenerSafely(
        listener: IServerLifecycleListener? = null,
        onFail: (String) -> Unit = {},
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            val name = listener?.let { it::class.simpleName } ?: "?"
            onFail("$name threw: ${t.message ?: t::class.simpleName ?: "<unknown>"}")
        }
    }
}
