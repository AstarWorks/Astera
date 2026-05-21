package com.astarworks.astera.core.domain.lifecycle

/**
 * Sealed error type for lifecycle transitions (Wave 5w / ADR-0089).
 *
 * Per CLAUDE.md / ADR-0001, application APIs return `Result<T, E>` rather
 * than throw. Listener crashes are logged + collected into
 * [Aggregate.failures], not thrown.
 */
public sealed interface LifecycleError {

    /** Caller tried to [boot] without first shutting down a prior boot. */
    public data object AlreadyBooted : LifecycleError

    /** Caller tried to [shutdown] before [boot] returned. */
    public data object NotBooted : LifecycleError

    /** Caller tried to [markReady] before [boot]. */
    public data object NotReadyableYet : LifecycleError

    /**
     * One or more listeners threw during a phase. Other listeners still ran;
     * the shutdown completed but with degraded confidence.
     */
    public data class Aggregate(
        val phase: ShutdownPhase?,
        val failures: List<ListenerFailure>,
    ) : LifecycleError {
        public data class ListenerFailure(
            val listenerSimpleName: String,
            val errorMessage: String,
        )
    }
}
