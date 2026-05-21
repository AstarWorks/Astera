package com.astarworks.astera.core.application.port.outbound.lifecycle

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.lifecycle.LifecycleError
import com.astarworks.astera.core.domain.lifecycle.LifecycleStateSnapshot
import com.astarworks.astera.core.domain.lifecycle.ShutdownReason

/**
 * Outbound port (Wave 5w / ADR-0089): orchestrates plugin boot order and
 * graceful shutdown.
 *
 * Owns the ordered list of [IServerLifecycleListener] subscriptions and
 * sequences them through the 4 lifecycle transitions: boot → ready → shutdown
 * (5-phase) → (optional) restart.
 *
 * **Why a port rather than concrete plugin code?**
 *
 * Phase 1 PoC ships `InMemoryServerBootstrapper` (no real signal handler);
 * Wave 6a / 6b will swap a `PaperServerBootstrapper` that hooks
 * `JavaPlugin.onDisable()` + JVM shutdown hook. The application-layer
 * orchestration logic stays identical.
 */
public interface IServerBootstrapper {

    /**
     * Register [listener] to receive lifecycle hooks.
     *
     * Registration order combines with [IServerLifecycleListener.priority] to
     * determine fire order (higher priority fires first inside the same
     * phase; ties resolved by registration order).
     */
    public fun register(listener: IServerLifecycleListener): Registration

    /**
     * Snapshot of currently active listeners ordered as they would fire (test
     * + introspection use only).
     */
    public fun listenersInFireOrder(): List<IServerLifecycleListener>

    /**
     * Trigger the `onBoot()` hook on every listener.
     *
     * Idempotent: subsequent calls re-fire only if [shutdown] has been called
     * in between (i.e. the bootstrapper supports boot → shutdown → boot
     * cycles for restart).
     */
    public fun boot(): Result<Unit, LifecycleError>

    /**
     * Trigger `onReady()` after every plugin has finished `onEnable`. The
     * caller (Paper platform) is responsible for invoking this after every
     * Astera plugin has come up.
     */
    public fun markReady(): Result<Unit, LifecycleError>

    /**
     * Trigger the graceful shutdown sequence (5 phases per
     * [com.astarworks.astera.core.domain.lifecycle.ShutdownPhase]).
     *
     * Returns once every phase has finished (or been logged-and-skipped on
     * listener crash). Idempotent: subsequent calls re-fire only after a
     * successful [boot] in between.
     */
    public fun shutdown(reason: ShutdownReason): Result<LifecycleStateSnapshot, LifecycleError>

    /**
     * Convenience: shutdown(Restart) followed by boot(). Implementations may
     * override for atomic restart semantics; default impl chains the two.
     */
    public fun restart(): Result<LifecycleStateSnapshot, LifecycleError> {
        val shutResult = shutdown(ShutdownReason.Restart)
        if (shutResult is Result.Failure) return shutResult
        val bootResult = boot()
        if (bootResult is Result.Failure) {
            return Result.Failure(bootResult.error)
        }
        return shutResult
    }

    public interface Registration {
        public fun unregister()
        public val isActive: Boolean
    }
}
