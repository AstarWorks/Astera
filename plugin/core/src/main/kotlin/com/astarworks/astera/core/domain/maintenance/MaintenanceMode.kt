package com.astarworks.astera.core.domain.maintenance

import java.time.Instant

/**
 * Server maintenance state (Wave E 5admin-ops, ADR-0122).
 *
 * In maintenance, only players with `astera.admin.maintenance.bypass` may
 * connect; everybody else sees a kick / login-denied message. Phase 1 owns
 * just the state — the actual login-gate listener lands in Wave 6b's
 * `BukkitEventAdapter` (no Paper imports allowed in `core.domain.*`, so the
 * gate must live in the adapter).
 *
 * `since` is the wall-clock at the last state flip; combined with
 * [On.scheduledOffAt] it lets the `/astera admin maintenance status` command tell
 * operators how long the server has been down and when (if ever) it lifts
 * automatically.
 *
 * Persistence: deliberately none in Phase 1 — a server restart implicitly
 * lifts maintenance. If you want persistent maintenance across restarts,
 * post-Phase-1 a `IMaintenanceModeRepository` port lands and the persistence
 * plugin owns the row.
 */
public sealed interface MaintenanceMode {

    /** When this state was entered. Useful for `/astera admin maintenance status`. */
    public val since: Instant

    /** Maintenance mode disabled — normal login flow. */
    public data class Off(override val since: Instant) : MaintenanceMode

    /**
     * Maintenance enabled. [reason] is shown to denied logins (and to ops in
     * the status readout); null means "no reason given". [scheduledOffAt] is
     * the optional auto-lift timestamp; null means "manual lift only".
     */
    public data class On(
        override val since: Instant,
        public val reason: String?,
        public val scheduledOffAt: Instant?,
    ) : MaintenanceMode
}
