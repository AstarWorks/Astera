package com.astarworks.astera.core.application.service.maintenance

import com.astarworks.astera.core.application.port.outbound.maintenance.IMaintenanceModeService
import com.astarworks.astera.core.domain.maintenance.MaintenanceMode
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory [IMaintenanceModeService] (Wave E 5admin-ops, ADR-0122).
 *
 * Thread-safe via an [AtomicReference]; state does not survive a server
 * restart (ADR-0122 §Decision — Phase 1 deliberately stays in-memory).
 *
 * Idempotent semantics:
 *  - `setOn` while already On preserves the original `since`; only `reason`
 *    and `scheduledOffAt` are updated. This matches admin UX expectations:
 *    "I changed the reason mid-incident, don't reset the clock."
 *  - `setOff` while already Off updates `since` to the new `now` — the
 *    intent is "I just confirmed maintenance is off", not a no-op.
 */
public class InMemoryMaintenanceModeService(
    initial: MaintenanceMode = MaintenanceMode.Off(Instant.EPOCH),
) : IMaintenanceModeService {

    private val ref = AtomicReference(initial)

    override fun current(): MaintenanceMode = ref.get()

    override fun setOn(
        now: Instant,
        reason: String?,
        scheduledOffAt: Instant?,
    ): MaintenanceMode.On {
        while (true) {
            val prev = ref.get()
            val since = if (prev is MaintenanceMode.On) prev.since else now
            val result = MaintenanceMode.On(
                since = since,
                reason = reason,
                scheduledOffAt = scheduledOffAt,
            )
            if (ref.compareAndSet(prev, result)) return result
        }
    }

    override fun setOff(now: Instant): MaintenanceMode.Off {
        val off = MaintenanceMode.Off(since = now)
        ref.set(off)
        return off
    }
}
