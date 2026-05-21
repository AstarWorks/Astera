package com.astarworks.astera.core.domain.lifecycle

/**
 * Reason a server shutdown was initiated (Wave 5w / ADR-0089).
 *
 * Carried by [com.astarworks.astera.core.application.port.outbound.lifecycle.IServerLifecycleListener.onShutdown]
 * and [com.astarworks.astera.core.domain.event.lifecycle.ServerShutdownStarted]. Listeners
 * use the reason to decide whether to persist "in-flight" state (planned
 * shutdown) vs treat the in-flight matches as crashed (process crash → next
 * boot restores via restic snapshot).
 */
public sealed interface ShutdownReason {
    /** Operator-triggered `stop` command. */
    public data object Planned : ShutdownReason
    /** Operator-triggered `restart` command or `/astera server restart`. */
    public data object Restart : ShutdownReason
    /** Process received SIGTERM or similar (e.g. k8s pod termination). */
    public data object Signal : ShutdownReason
    /** JVM shutdown hook fired due to crash; listeners may have limited time. */
    public data object Crash : ShutdownReason
}
