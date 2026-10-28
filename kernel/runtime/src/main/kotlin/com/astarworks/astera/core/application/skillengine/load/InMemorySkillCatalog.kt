package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.application.skillengine.compose.ComposedSkill
import com.astarworks.astera.core.application.skillengine.compose.PartCatalog
import com.astarworks.astera.core.application.skillengine.compose.SkillCompiler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill

/**
 * Thread-safe catalog over the v3 pipeline: [SkillDocumentDecoder] then [SkillCompiler]. A composed tree that is not a v3 `kind: Skill`
 * document fails its own decode (named per id in [SkillLoadReport.failed]); a failed decode/compile keeps the previous [CompiledSkill];
 * casts hold their own reference.
 */
public class InMemorySkillCatalog(
    private val decoder: SkillDocumentDecoder,
    private val compiler: SkillCompiler,
    private val parts: PartCatalog,
    private val knownParts: Set<String>,
) : SkillCatalog {
    private val skills = java.util.concurrent.ConcurrentHashMap<String, CompiledSkill>()

    override fun get(skillId: String): CompiledSkill? = skills[skillId]

    override fun ids(): Set<String> = skills.keys.toSet()

    @Synchronized
    override fun reload(composed: List<ComposedSkill>): SkillLoadReport {
        val failed = LinkedHashMap<String, SkillDecodeError>()
        val env = GrammarEnv(knownParts, skills.keys + composed.map { it.id })
        val defs = LinkedHashMap<String, SkillDef>()
        for (c in composed) {
            when (val d = decoder.decode(c.id, c.tree, env)) {
                is Result.Success -> defs[c.id] = d.value
                is Result.Failure -> failed[c.id] = d.error
            }
        }
        val batch = MapSkillCatalogView(defs.values)
        val view = object : SkillCatalogView {
            override fun skill(id: String): SkillDef? = batch.skill(id) ?: skills[id]?.def
        }
        val ok = LinkedHashMap<String, CompiledSkill>()
        for ((id, def) in defs) {
            when (val r = compiler.compile(def, parts, view)) {
                is Result.Success -> ok[id] = r.value
                is Result.Failure -> failed[id] = r.error
            }
        }
        skills.putAll(ok)
        return SkillLoadReport(ok.keys.toList(), failed)
    }

    @Synchronized
    override fun remove(skillId: String) {
        skills.remove(skillId)
    }
}
