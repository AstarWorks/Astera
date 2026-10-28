package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.application.skillengine.compose.ComposedSkill
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill

/** Result of reloading one skill (and, for a base, its variants). */
public data class SkillLoadReport(
    val loaded: List<String>,
    val failed: Map<String, SkillDecodeError>,
)

/**
 * Registry of compiled skills with per-skill reload (design 7): a failed reload keeps the old
 * definition; casts in progress hold their [CompiledSkill] and finish on it.
 */
public interface SkillCatalog {
    public fun get(skillId: String): CompiledSkill?
    public fun ids(): Set<String>

    /** Decode + compile + swap atomically for the given composed skills; others are untouched. */
    public fun reload(composed: List<ComposedSkill>): SkillLoadReport

    public fun remove(skillId: String)
}
