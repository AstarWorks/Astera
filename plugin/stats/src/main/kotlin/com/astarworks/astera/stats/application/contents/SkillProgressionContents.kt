package com.astarworks.astera.stats.application.contents

import com.astarworks.astera.core.application.contents.YamlDynamicContents
import com.astarworks.astera.core.application.port.outbound.ISkillPerkRegistry
import com.astarworks.astera.core.application.port.outbound.ISkillXpCurveRegistry
import com.astarworks.astera.core.domain.progression.SkillKind
import com.astarworks.astera.core.domain.progression.SkillProgressionDefinition
import com.astarworks.astera.core.domain.progression.SkillProgressionPerk
import com.astarworks.astera.core.domain.progression.SkillXpCurve
import com.astarworks.astera.stats.application.config.SkillProgressionYamlConfig

/**
 * `Contents<SkillKind, SkillProgressionDefinition>` for the
 * `content/skills-progression/` registry (Wave 5multi-skill, ADR-0101).
 *
 * Implements both [ISkillXpCurveRegistry] and [ISkillPerkRegistry] so the
 * use case + level-up listeners read from a single in-process snapshot
 * (atomic-reference inherited from [YamlDynamicContents]).
 *
 * Falls back to [SkillXpCurve.exponential] when a kind has no YAML file —
 * keeps the system bootstrappable in a fresh deployment and on tests that
 * never seed `content/skills-progression/`.
 */
public class SkillProgressionContents(
    private val defaultCurve: SkillXpCurve = SkillXpCurve.exponential(),
) : YamlDynamicContents<SkillKind, SkillProgressionDefinition, SkillProgressionYamlConfig>(
    categoryName = "skills-progression",
    deserializer = SkillProgressionYamlConfig.serializer(),
), ISkillXpCurveRegistry, ISkillPerkRegistry {

    override fun toEntry(config: SkillProgressionYamlConfig): Pair<SkillKind, SkillProgressionDefinition> {
        val spec = config.toSpec()
        return spec.kind to spec
    }

    // ─── ISkillXpCurveRegistry ───
    override fun curveOf(kind: SkillKind): SkillXpCurve =
        find(kind)?.curve ?: defaultCurve

    override fun explicit(): Set<SkillKind> = all().map { it.kind }.toSet()

    // ─── ISkillPerkRegistry ───
    override fun perksFor(kind: SkillKind): List<SkillProgressionPerk> =
        find(kind)?.perks ?: emptyList()

    /** Convenience for tests / admin commands. */
    public fun register(spec: SkillProgressionDefinition) {
        register(spec.kind, spec)
    }
}
