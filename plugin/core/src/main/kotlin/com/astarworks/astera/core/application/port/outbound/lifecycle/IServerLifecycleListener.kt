package com.astarworks.astera.core.application.port.outbound.lifecycle

import com.astarworks.astera.core.domain.lifecycle.ShutdownPhase
import com.astarworks.astera.core.domain.lifecycle.ShutdownReason

/**
 * Outbound port (Wave 5w / ADR-0089): plugin-level hook for server lifecycle.
 *
 * Implementations register on the [com.astarworks.astera.core.application.services.ServiceRegistry]
 * (priority-ordered). The [IServerBootstrapper] visits listeners on each
 * transition in registration / priority order.
 *
 * **Why a port and not a sealed event subscription?**
 *
 * Lifecycle hooks must run in a deterministic ordered sequence: e.g. shutdown
 * must do `StopAcceptingPlayers → SaveInFlightMatches → FlushRewardOutbox →
 * CloseDatabase → DisablePlugins`. An [com.astarworks.astera.core.application.port.outbound.IDomainEventBus]
 * subscription has no ordering guarantee across plugins — a hook port does.
 *
 * Listeners must be **idempotent + total** (return cleanly even if called
 * twice or if the previous transition failed mid-flight).
 */
public interface IServerLifecycleListener {

    /**
     * Stable ordering hint. Higher fires earlier inside the same phase.
     *
     * Default 0 puts a listener in the "middle bucket". Listeners that must
     * run last (e.g. database close) use a deeply negative priority; ones
     * that must run first (e.g. "stop accepting players") use a high
     * priority.
     */
    public val priority: Int get() = 0

    /**
     * Fires once at plugin onEnable, after [com.astarworks.astera.core.application.services.ServiceRegistry]
     * is populated but before any scheduler task starts.
     */
    public fun onBoot() {}

    /**
     * Fires when [IServerBootstrapper.markReady] is invoked, signalling that
     * every plugin has finished its `onEnable` and the server is accepting
     * players.
     */
    public fun onReady() {}

    /**
     * Fires on shutdown initiation. Reason carries the trigger.
     *
     * The default flow is `onShutdown(reason)` first (overall notice), then
     * one `onShutdownPhase(phase)` per [ShutdownPhase] in [ShutdownPhase.ORDER].
     * Listeners that need fine-grained phase routing override `onShutdownPhase`
     * instead of (or in addition to) `onShutdown`.
     */
    public fun onShutdown(reason: ShutdownReason) {}

    /**
     * Fires once per [ShutdownPhase] during shutdown, in [ShutdownPhase.ORDER].
     * Each listener typically implements only the phase it owns
     * (e.g. Match plugin owns [ShutdownPhase.SaveInFlightMatches]).
     *
     * **Must not throw.** Exceptions are caught by the bootstrapper and logged
     * but do not abort the remaining phases — a crashed FlushRewardOutbox
     * listener still lets CloseDatabase run.
     */
    public fun onShutdownPhase(phase: ShutdownPhase, reason: ShutdownReason) {}

    /**
     * Fires on planned restart (admin `/astera server restart` or similar).
     * Default delegates to [onShutdown] with [ShutdownReason.Restart].
     */
    public fun onRestart() {
        onShutdown(ShutdownReason.Restart)
    }
}
