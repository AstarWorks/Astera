package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.barrier.BarrierId
import com.astarworks.astera.match.application.port.outbound.IBarrierGateway
import com.astarworks.astera.match.domain.barrier.Barrier
import com.astarworks.astera.match.domain.barrier.BarrierStatus

/**
 * Default in-memory [IBarrierGateway] (Wave 5d).
 *
 * Tracks barriers as a plain `Map<BarrierId, Barrier>` plus a parallel
 * `Map<BarrierId, BarrierStatus>`. There is no Bukkit `DisplayEntity` here
 * and there are no per-tick side effects: this gateway is the Fake half of
 * the "Wave 5d Fake / Wave 6 Paper" pair noted on the [IBarrierGateway]
 * port doc. Wave 6's `PaperBarrierGateway` will spawn the actual visual.
 *
 * # Status semantics
 *
 * Freshly spawned barriers default to [BarrierStatus.Active]. Use cases
 * mutate status through [updateStatus] (e.g. `Disabled` from a sabotage
 * skill, `Destroyed` from `DestroyBarrierUseCase`). [updateStatus] is a
 * no-op for unknown ids so the gateway is robust against stale references
 * (a use case that races with `despawn` will not crash the server).
 *
 * # Thread-safety
 *
 * **Not thread-safe** — same invariant as [InMemoryGeneratorRepository]:
 * all match-state mutation runs on the server main thread per the
 * `IScheduler` regional contract. The plain `MutableMap` is intentional;
 * concurrent collections would imply a guarantee Astera does not provide.
 */
public class InMemoryBarrierGateway : IBarrierGateway {

    private val store: MutableMap<BarrierId, Barrier> = mutableMapOf()
    private val statuses: MutableMap<BarrierId, BarrierStatus> = mutableMapOf()

    override fun spawn(barrier: Barrier) {
        store[barrier.id] = barrier
        statuses[barrier.id] = BarrierStatus.Active
    }

    override fun despawn(id: BarrierId) {
        store.remove(id)
        statuses.remove(id)
    }

    override fun updateStatus(id: BarrierId, status: BarrierStatus) {
        if (id in store) statuses[id] = status
    }

    override fun all(): List<Barrier> = store.values.toList()

    /**
     * Test-only helper for inspecting current status. **Not** part of the
     * [IBarrierGateway] interface — production code should not depend on
     * the gateway exposing its internal status table. Tests use this to
     * assert lifecycle transitions without standing up a domain-event
     * subscriber.
     */
    public fun statusOf(id: BarrierId): BarrierStatus? = statuses[id]
}
