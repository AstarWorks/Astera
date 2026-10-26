package com.astarworks.astera.core.application.port.outbound.maintenance

import com.astarworks.astera.core.domain.maintenance.MaintenanceMode
import java.time.Instant

/**
 * Outbound port: read and toggle the server's [MaintenanceMode] (Wave E
 * 5admin-ops, ADR-0122).
 *
 * Single mutable cell of state. The `/astera admin maintenance` command consumes
 * this; the future Wave 6b login-gate listener consumes
 * [current] on every `PlayerLogin` and denies non-admins when the result is
 * [MaintenanceMode.On].
 *
 * Implementations must be thread-safe — toggling happens on the main thread
 * (command thread), reading happens on the login thread.
 */
public interface IMaintenanceModeService {

    /** Current state. Cheap; safe to call per-login. */
    public fun current(): MaintenanceMode

    /**
     * Flip into [MaintenanceMode.On]. Idempotent — calling while already On
     * updates `reason` / `scheduledOffAt` but keeps the original `since`.
     */
    public fun setOn(now: Instant, reason: String?, scheduledOffAt: Instant?): MaintenanceMode.On

    /**
     * Flip into [MaintenanceMode.Off]. Idempotent. Returns the new Off state
     * (with `since = now`); the previous On state's age can be inferred from
     * the difference at the call site if the caller cached it pre-flip.
     */
    public fun setOff(now: Instant): MaintenanceMode.Off
}
