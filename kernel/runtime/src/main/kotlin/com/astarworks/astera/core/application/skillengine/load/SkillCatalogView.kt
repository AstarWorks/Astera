package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.domain.skillengine.model.SkillDef

/** Read-only view of the sub-skills a skill may call (`use: skill:<id>`, ADR-0303 section 9). */
public interface SkillCatalogView {
    public fun skill(id: String): SkillDef?
}

/** A fixed set of skill definitions, e.g. the documents of one inline YAML file or a reload batch. */
public class MapSkillCatalogView(skills: Collection<SkillDef>) : SkillCatalogView {
    private val byId: Map<String, SkillDef> = skills.associateBy { it.id }
    override fun skill(id: String): SkillDef? = byId[id]
}

/** The compiled skills of a [SkillCatalog] as callable sub-skills; reads through, so a reload is seen by the next compile. */
public fun SkillCatalog.asView(): SkillCatalogView = object : SkillCatalogView {
    override fun skill(id: String): SkillDef? = get(id)?.def
}
