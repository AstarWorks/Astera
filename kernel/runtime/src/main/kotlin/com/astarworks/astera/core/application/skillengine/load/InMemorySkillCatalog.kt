package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.application.skillengine.compose.ComposedSkill
import com.astarworks.astera.core.domain.Result

/** Thread-safe catalog. A failed decode/compile keeps the previous [CompiledSkill]; casts hold their own reference. */
public class InMemorySkillCatalog(
    private val decoder: SkillDecoder,
    private val compiler: SkillCompiler,
) : SkillCatalog {
    private val skills = java.util.concurrent.ConcurrentHashMap<String, CompiledSkill>()

    override fun get(skillId: String): CompiledSkill? = skills[skillId]

    override fun ids(): Set<String> = skills.keys.toSet()

    @Synchronized
    override fun reload(composed: List<ComposedSkill>): SkillLoadReport {
        val ok = LinkedHashMap<String, CompiledSkill>()
        val failed = LinkedHashMap<String, SkillDecodeError>()
        for (c in composed) {
            val decoded = decoder.decode(c)
            if (decoded is Result.Failure) {
                failed[c.id] = decoded.error
                continue
            }
            when (val r = compiler.compile((decoded as Result.Success).value)) {
                is Result.Success -> ok[c.id] = r.value
                is Result.Failure -> failed[c.id] = r.error
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
