package com.astarworks.astera.core.domain.skillengine.table

import com.astarworks.astera.core.domain.skillengine.model.SkillDef

/** A decoded v3 skill plus its expansion table: the whole product of compile (ADR-0303 C1). */
public data class CompiledSkill(
    val def: SkillDef,
    val table: ExpansionTable,
    /** Part ids this skill inlined (reload dependency, ADR-0303 section 12). */
    val usedParts: Set<String> = emptySet(),
    /** Skill ids this skill calls with `use: skill:<id>` (reload dependency). */
    val usedSkills: Set<String> = emptySet(),
    /** The strongest patch kind behind this id (`base` for none; a variant: its own patches): `skin`, `balance` or `mechanic`. Set by the catalog from the composer. */
    val patchKind: String = "base",
)
