package com.astarworks.astera.core.application.wiring

import com.astarworks.astera.core.domain.Result

/**
 * Operator-facing rewire port (ADR-0311, P1): swap one plugin's wiring generation for a fresh one while the server
 * keeps running. Implemented by the platform's `RewireCoordinator`; the `astera admin rewire` command calls it.
 */
public interface IRewireService {

    /** Plugin ids (plugin.yml names) that support rewire right now. */
    public fun rewirable(): List<String>

    /**
     * Rewire [plugin] (`economy`, `Astera-Economy`, ...) and every rewirable plugin that depends on it.
     * A failed rewire leaves the running generation untouched.
     *
     * With [reconnect] the plugin first re-establishes its external connection (Persistence: a new Postgres pool is
     * built and swapped in, the old one drained and closed) before the new generation is wired. Only plugins that
     * own a connection accept it.
     */
    public fun rewire(plugin: String, reconnect: Boolean = false): Result<RewireReport, RewireError>

    /** One line per rewirable plugin: id, generation, registration counts. */
    public fun status(): List<String>
}

/** Registration counts of one wiring generation (what a rewire must leave equal). */
public data class WiringCounts(
    val services: Int,
    val subscriptions: Int,
    val intents: Int,
    val tasks: Int,
    val reloadables: Int,
    val listeners: Int,
) {
    override fun toString(): String =
        "services=$services subscriptions=$subscriptions intents=$intents tasks=$tasks " +
            "reloadables=$reloadables listeners=$listeners"
}

/** What a rewire did to one plugin. */
public data class RewireChange(
    val owner: WiringOwner,
    val fromGen: Int,
    val toGen: Int,
    val before: WiringCounts,
    val after: WiringCounts,
)

/** Outcome of a successful rewire: one [RewireChange] per rewired plugin plus any count regressions. */
public data class RewireReport(
    val changes: List<RewireChange>,
    val warnings: List<String>,
) {
    public fun summary(): String =
        changes.joinToString("; ") { "${it.owner} gen ${it.fromGen} -> ${it.toGen} (${it.after})" }
}

/** Why a rewire did not happen. [code] is the stable word the console prints. */
public sealed interface RewireError {
    public val code: String
    public val message: String

    public data class UnknownPlugin(val name: String, val known: List<String>) : RewireError {
        override val code: String get() = "UNKNOWN_PLUGIN"
        override val message: String get() = "unknown plugin '$name' (known: ${known.joinToString()})"
    }

    public data object InProgress : RewireError {
        override val code: String get() = "IN_PROGRESS"
        override val message: String get() = "another rewire is running"
    }

    /** The plugin has no rewire lifecycle yet (P2+) or its dependency is not up. */
    public data class RestartRequired(val plugin: String, val reason: String) : RewireError {
        override val code: String get() = "RESTART_REQUIRED"
        override val message: String get() = "$plugin cannot be rewired: $reason"
    }

    /** `--reconnect` was refused or failed; nothing was rewired and the old connection keeps serving. */
    public data class ReconnectFailed(val plugin: String, val cause: String) : RewireError {
        override val code: String get() = "RECONNECT_FAILED"
        override val message: String get() = "$plugin did not reconnect, old connection kept: $cause"
    }

    /** Generation N+1 failed to wire; generation N is untouched. */
    public data class WireFailed(val plugin: String, val cause: String) : RewireError {
        override val code: String get() = "WIRE_FAILED"
        override val message: String get() = "$plugin failed to wire, old generation kept: $cause"
    }

    /** Generation N+1 failed to bind; [rolledBack] says whether generation N is running again. */
    public data class BindFailed(val plugin: String, val cause: String, val rolledBack: Boolean) : RewireError {
        override val code: String get() = "BIND_FAILED"
        override val message: String
            get() = "$plugin failed to bind ($cause); " +
                if (rolledBack) "rolled back to the old generation" else "ROLLBACK FAILED, restart the server"
    }
}
