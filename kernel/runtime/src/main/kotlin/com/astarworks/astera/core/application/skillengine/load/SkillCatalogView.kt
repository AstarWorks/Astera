package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.domain.skillengine.model.SkillDef

/** Read-only view of the sub-skills a skill may call (`use: skill:<id>`). Stub: W1-15 implements it. */
public interface SkillCatalogView {
    public fun skill(id: String): SkillDef?
}
