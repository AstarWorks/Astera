package com.astarworks.astera.stats.application.service.progression

import com.astarworks.astera.core.application.port.outbound.ISkillPerkRegistry
import com.astarworks.astera.core.domain.progression.SkillKind
import com.astarworks.astera.core.domain.progression.SkillProgressionPerk

/**
 * In-memory [ISkillPerkRegistry] (Wave 5multi-skill, ADR-0101).
 *
 * Holds a per-axis sorted list. The YAML content registry
 * ([com.astarworks.astera.stats.application.contents.SkillProgressionContents])
 * pushes entries here at bootstrap / reload.
 */
public class InMemorySkillPerkRegistry : ISkillPerkRegistry {

    private val map = mutableMapOf<SkillKind, List<SkillProgressionPerk>>()

    public fun register(kind: SkillKind, perks: List<SkillProgressionPerk>) {
        map[kind] = perks.sortedBy { it.level }
    }

    public fun clear() {
        map.clear()
    }

    override fun perksFor(kind: SkillKind): List<SkillProgressionPerk> =
        map[kind] ?: emptyList()
}
