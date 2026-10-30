package com.astarworks.astera.combat.domain.skill

/** Admin panel grouping of a [SkillSpec]; the v3 engine does not use it (every skill is [SEQUENCE]). */
public enum class SkillArchetype {
    PROJECTILE,
    MELEE,
    AOE,
    BUFF,
    TELEPORT,
    SUMMON,

    /** Pure mechanics container: runs the `cast` phase only (no hit check, no status). For skills that are entirely timelines. */
    SEQUENCE,
}
