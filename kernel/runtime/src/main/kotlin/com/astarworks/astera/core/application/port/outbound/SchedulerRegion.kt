package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID

/**
 * Where a scheduled task wants to run.
 *
 * PaperScheduler (Wave 1) ignores region — Paper has one main thread. A future
 * FoliaScheduler routes per-region. Designed up front so callers don't need to
 * be retrofitted later.
 */
public sealed class SchedulerRegion {
    /** Run on the global region / main thread. Default for all Paper tasks. */
    public data object Global : SchedulerRegion()

    /** Run on the region owning the given entity (Folia). */
    public data class PerEntity(val entityId: UUID) : SchedulerRegion()

    /** Run on the region owning chunk (cx, cz) in the given world (Folia). */
    public data class PerChunk(val world: WorldId, val cx: Int, val cz: Int) : SchedulerRegion()
}
