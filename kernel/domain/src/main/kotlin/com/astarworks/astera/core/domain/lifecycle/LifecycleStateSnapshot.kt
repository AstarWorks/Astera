package com.astarworks.astera.core.domain.lifecycle

import java.time.Instant

/**
 * Result of a completed shutdown sequence (Wave 5w / ADR-0089).
 *
 * Captures which phases actually ran (a listener may abort early, e.g. JVM
 * shutdown hook ran out of time before [ShutdownPhase.DisablePlugins]).
 */
public data class LifecycleStateSnapshot(
    val reason: ShutdownReason,
    val startedAt: Instant,
    val completedAt: Instant,
    val phasesExecuted: List<ShutdownPhase>,
    val listenerFailures: List<LifecycleError.Aggregate.ListenerFailure>,
)
