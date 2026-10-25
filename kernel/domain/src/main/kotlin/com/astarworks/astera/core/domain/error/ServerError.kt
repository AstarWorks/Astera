package com.astarworks.astera.core.domain.error

import java.time.Instant

/**
 * Structured server-side error report (Wave 5server-harden / ADR-0123).
 *
 * Carried through [com.astarworks.astera.core.application.port.outbound.error.IErrorReporter].
 * Sealed so the audit-log writer + admin-broadcast subscriber can branch on
 * severity exhaustively.
 *
 * # Why not throw / log a string
 *
 * - Throwing leaks JVM stack traces into operator-facing channels.
 * - String logging loses the severity dimension after the line is written.
 * - A typed envelope lets us route Critical → admin broadcast,
 *   Warning → log, Info → debug — independent of where the report originates.
 *
 * # Caller contract
 *
 * Callers must build a [ServerError] from a sealed [ErrorSource] (use case /
 * service / lifecycle hook). Free-form `source = "..."` is intentionally
 * absent — adding a category here forces a sealed-class extension at refactor
 * time, which the Konsist suite catches as a missing arm.
 */
public sealed class ServerError {
    /** When the condition occurred (caller-supplied for determinism in tests). */
    public abstract val occurredAt: Instant

    /** Short, operator-readable summary. */
    public abstract val summary: String

    /** Where in Astera the report originated. */
    public abstract val source: ErrorSource

    /** Severity tier — controls the routing fan-out. */
    public abstract val severity: Severity

    public enum class Severity { Critical, Warning, Info }

    /**
     * Unhandled failure inside a use case or scheduled job. Critical by default.
     */
    public data class Critical(
        override val occurredAt: Instant,
        override val summary: String,
        override val source: ErrorSource,
        /** Optional throwable class name + first line — never the full stack. */
        val causeShortDescription: String? = null,
    ) : ServerError() {
        override val severity: Severity = Severity.Critical
    }

    /**
     * Recoverable but operator-attention-worthy condition (e.g. a content
     * registry returned no entries after a hot reload).
     */
    public data class Warning(
        override val occurredAt: Instant,
        override val summary: String,
        override val source: ErrorSource,
        val context: Map<String, String> = emptyMap(),
    ) : ServerError() {
        override val severity: Severity = Severity.Warning
    }

    /**
     * Informational note useful in the audit trail but not surfaced to admins.
     */
    public data class Info(
        override val occurredAt: Instant,
        override val summary: String,
        override val source: ErrorSource,
        val context: Map<String, String> = emptyMap(),
    ) : ServerError() {
        override val severity: Severity = Severity.Info
    }
}

/**
 * Sealed classification of where a [ServerError] originated. Add a new
 * variant rather than synthesising a free-form string; the Konsist /
 * exhaustive-when discipline keeps routing total.
 */
public sealed class ErrorSource {
    public abstract val identifier: String

    /** Plugin entry-point or DI wiring (`onEnable`, `onDisable`, `onLoad`). */
    public data class Lifecycle(val pluginName: String, val phase: String) : ErrorSource() {
        override val identifier: String = "$pluginName/$phase"
    }

    /** A use case in `*.application.usecase.*` — pass the simple class name. */
    public data class UseCase(val name: String) : ErrorSource() {
        override val identifier: String = "use-case/$name"
    }

    /** A scheduler / job — pass a stable id, not the runnable hashCode. */
    public data class Scheduler(val jobId: String) : ErrorSource() {
        override val identifier: String = "scheduler/$jobId"
    }

    /** A content loader / validator. */
    public data class Content(val category: String) : ErrorSource() {
        override val identifier: String = "content/$category"
    }

    /** A repository / persistence path. */
    public data class Persistence(val repositoryName: String) : ErrorSource() {
        override val identifier: String = "persistence/$repositoryName"
    }

    /** Anything else — should be rare; refactor towards a typed variant
     *  when a free-form bucket starts growing. */
    public data class Other(val name: String) : ErrorSource() {
        override val identifier: String = "other/$name"
    }
}
