package com.astarworks.astera.combat.testfixtures

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectRegistry
import com.astarworks.astera.combat.domain.status.StatusEffectId
import com.astarworks.astera.combat.domain.status.StatusEffectSpec

/**
 * In-memory [IStatusEffectRegistry] for use case tests.
 *
 * Call [register] to seed specs; [find] / [all] reflect registered content.
 */
public class FakeStatusEffectRegistry : IStatusEffectRegistry {
    private val store: MutableMap<StatusEffectId, StatusEffectSpec> = mutableMapOf()

    public fun register(spec: StatusEffectSpec) {
        store[spec.id] = spec
    }

    override fun find(id: StatusEffectId): StatusEffectSpec? = store[id]
    override fun all(): Collection<StatusEffectSpec> = store.values.toList()
}
