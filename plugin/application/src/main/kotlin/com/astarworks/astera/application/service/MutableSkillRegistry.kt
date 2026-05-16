package com.astarworks.astera.application.service

import com.astarworks.astera.application.port.outbound.ISkillRegistry
import com.astarworks.astera.domain.model.skill.SkillId
import com.astarworks.astera.domain.model.skill.SkillSpec
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory skill registry. Populated by [SkillLoaderService] at startup
 * (Stage 1 J of the Phase 2 mid Batch 2 plan).
 *
 * Phase 4 may swap to a DB-backed implementation that reads from
 * `persistence-postgres` — the application layer only depends on
 * [ISkillRegistry], so the platform module's Koin module is the only place
 * that changes.
 */
public class MutableSkillRegistry : ISkillRegistry {
    private val store = ConcurrentHashMap<SkillId, SkillSpec>()

    public fun register(spec: SkillSpec) {
        store[spec.id] = spec
    }

    public fun clear() {
        store.clear()
    }

    override fun find(id: SkillId): SkillSpec? = store[id]
    override fun all(): Collection<SkillSpec> = store.values.toList()
}
