package com.astarworks.astera.core.domain.skillengine.table

import com.astarworks.astera.core.domain.skillengine.model.SkillDef

/** A decoded v3 skill plus its expansion table: the whole product of compile (ADR-0303 C1). */
public data class CompiledSkill(val def: SkillDef, val table: ExpansionTable)
