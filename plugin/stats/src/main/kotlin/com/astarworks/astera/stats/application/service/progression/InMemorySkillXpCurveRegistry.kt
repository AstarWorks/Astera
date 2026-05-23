package com.astarworks.astera.stats.application.service.progression

import com.astarworks.astera.core.application.port.outbound.ISkillXpCurveRegistry
import com.astarworks.astera.core.domain.progression.SkillKind
import com.astarworks.astera.core.domain.progression.SkillXpCurve

/**
 * In-memory [ISkillXpCurveRegistry] (Wave 5multi-skill, ADR-0101).
 *
 * Holds an optional per-axis [SkillXpCurve] map; any axis missing from the
 * map resolves to [defaultCurve] (which itself defaults to
 * [SkillXpCurve.exponential]). The YAML content registry
 * ([com.astarworks.astera.stats.application.contents.SkillProgressionContents])
 * pushes entries here at bootstrap / reload.
 */
public class InMemorySkillXpCurveRegistry(
    private val defaultCurve: SkillXpCurve = SkillXpCurve.exponential(),
) : ISkillXpCurveRegistry {

    private val map = mutableMapOf<SkillKind, SkillXpCurve>()

    public fun register(kind: SkillKind, curve: SkillXpCurve) {
        map[kind] = curve
    }

    public fun clear() {
        map.clear()
    }

    override fun curveOf(kind: SkillKind): SkillXpCurve = map[kind] ?: defaultCurve

    override fun explicit(): Set<SkillKind> = map.keys.toSet()
}
