package com.astarworks.astera.combat.testfixtures

import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.core.domain.skill.SkillId

/**
 * In-memory [ISkillRegistry] for use case tests.
 *
 * Call [register] to seed specs; [find] / [all] reflect registered content.
 */
public class FakeSkillRegistry : ISkillRegistry {
    private val store: MutableMap<SkillId, SkillSpec> = mutableMapOf()

    public fun register(spec: SkillSpec) {
        store[spec.id] = spec
    }

    override fun find(id: SkillId): SkillSpec? = store[id]
    override fun all(): Collection<SkillSpec> = store.values.toList()
}
