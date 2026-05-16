package com.astarworks.astera.application.service

import com.astarworks.astera.application.port.outbound.IStatusEffectRegistry
import com.astarworks.astera.domain.model.status.StatusEffectId
import com.astarworks.astera.domain.model.status.StatusEffectSpec
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory status-effect registry. Populated by [StatusEffectLoaderService]
 * at startup (Stage 1 J of the Phase 2 mid Batch 2 plan).
 *
 * Phase 4 may swap to a DB-backed implementation that reads from
 * `persistence-postgres` — the application layer only depends on
 * [IStatusEffectRegistry], so the platform module's Koin module is the only
 * place that changes.
 */
public class MutableStatusRegistry : IStatusEffectRegistry {
    private val store = ConcurrentHashMap<StatusEffectId, StatusEffectSpec>()

    public fun register(spec: StatusEffectSpec) {
        store[spec.id] = spec
    }

    public fun clear() {
        store.clear()
    }

    override fun find(id: StatusEffectId): StatusEffectSpec? = store[id]
    override fun all(): Collection<StatusEffectSpec> = store.values.toList()
}
