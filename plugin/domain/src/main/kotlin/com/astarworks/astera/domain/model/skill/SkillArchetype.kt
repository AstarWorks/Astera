package com.astarworks.astera.domain.model.skill

/**
 * High-level skill family. The archetype constrains which [com.astarworks.astera.domain.model.skill.params.SkillParams]
 * variant a [SkillSpec] may carry; validation of that pairing lives in the
 * application layer when YAML is materialized into specs.
 */
public enum class SkillArchetype {
    PROJECTILE,
    MELEE,
    AOE,
    BUFF,
    TELEPORT,
    SUMMON,
}
