package com.astarworks.astera.core.domain.progression

/**
 * Full YAML-derived definition for a single progression axis
 * (Wave 5multi-skill, ADR-0101).
 *
 * Bundles the [SkillXpCurve] and the level-gated [SkillProgressionPerk] list
 * so the YAML loader emits one entry per `content/skills-progression/<axis>.yaml`
 * file and downstream registries pull what they need (curve / perks)
 * separately.
 *
 * Invariant: every [perks] entry's `level` must be reachable on [curve]
 * (`level <= curve.maxLevel`). Enforced at construction to surface YAML
 * typos at boot, not at level-up time.
 */
public data class SkillProgressionDefinition(
    public val kind: SkillKind,
    public val curve: SkillXpCurve,
    public val perks: List<SkillProgressionPerk>,
) {
    init {
        perks.forEach { perk ->
            require(perk.level <= curve.maxLevel) {
                "Perk '${perk.id}' on $kind requires level ${perk.level}, " +
                    "but the configured XP curve caps at ${curve.maxLevel}."
            }
        }
        val duplicateId = perks.groupingBy { it.id }.eachCount().entries
            .firstOrNull { it.value > 1 }?.key
        require(duplicateId == null) {
            "Duplicate perk id '$duplicateId' under skill $kind"
        }
    }
}
