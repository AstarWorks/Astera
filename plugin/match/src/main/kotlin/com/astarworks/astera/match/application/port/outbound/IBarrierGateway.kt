package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.barrier.BarrierId
import com.astarworks.astera.match.domain.barrier.Barrier
import com.astarworks.astera.match.domain.barrier.BarrierStatus

/**
 * Outbound port: spawn / despawn / status updates for [Barrier] instances
 * (Wave 5d).
 *
 * Wave 5d ships `InMemoryBarrierGateway` (Fake — tracks barriers in a Map,
 * no visual side-effect). Wave 6 lands `PaperBarrierGateway` with Bukkit
 * `DisplayEntity` visuals.
 *
 * Match-local port (lives in `plugin/match`, not `plugin/core`) because
 * barriers are a Match-gameplay concept: no sibling plugin needs the
 * spawn / despawn surface.
 */
public interface IBarrierGateway {
    public fun spawn(barrier: Barrier)
    public fun despawn(id: BarrierId)
    public fun updateStatus(id: BarrierId, status: BarrierStatus)

    /** Snapshot of currently tracked barriers (used by `BarrierStatusScheduler`). */
    public fun all(): List<Barrier>
}
