package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.application.skillengine.compose.ComposedSkill
import com.astarworks.astera.core.application.skillengine.compose.OverlayPartCatalog
import com.astarworks.astera.core.application.skillengine.compose.PartCatalog
import com.astarworks.astera.core.application.skillengine.compose.SkillCompiler
import com.astarworks.astera.core.application.skillengine.compose.SkinGameplayParityCheck
import com.astarworks.astera.core.application.skillengine.compose.UseCollector
import com.astarworks.astera.core.application.skillengine.part.PartAbort
import com.astarworks.astera.core.application.skillengine.part.PartParser
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill

/**
 * Thread-safe catalog over the v3 pipeline: [SkillDocumentDecoder] then [SkillCompiler]. A composed tree that is not a v3 `kind: Skill`
 * document fails its own decode (named per id in [SkillLoadReport.failed]); a failed decode/compile keeps the previous [CompiledSkill];
 * casts hold their own reference.
 *
 * Dependents (ADR-0303 section 9, W1-26): parts inline and skills are called at compile, so the composer does not recompose
 * callers. This catalog does: every [CompiledSkill] carries `usedParts` / `usedSkills` (the closure of what its machine calls,
 * recorded here from the decoded machine and merged with what the compiler reports), and [reload] recompiles every skill whose
 * sets meet a changed part or skill id, transitively. A `kind: Part` entry in `composed` replaces that part in the catalog's part
 * overlay before the dependents are compiled.
 */
public class InMemorySkillCatalog(
    private val decoder: SkillDocumentDecoder,
    private val compiler: SkillCompiler,
    parts: PartCatalog,
    knownParts: Set<String>,
) : SkillCatalog {
    private val skills = java.util.concurrent.ConcurrentHashMap<String, CompiledSkill>()
    private val partOverlay = OverlayPartCatalog(parts, knownParts)

    override fun get(skillId: String): CompiledSkill? = skills[skillId]

    override fun ids(): Set<String> = skills.keys.toSet()

    @Volatile
    private var calleeOnly: Map<String, List<String>> = emptyMap()

    override fun calleeOnlyCallers(skillId: String): List<String>? = calleeOnly[skillId]

    /** Rebuilds the callee-only marker from the current skills (called at the end of every load, so a cast never has to expand to find out). */
    private fun markCalleeOnly() {
        calleeOnly = skills.values.filter { it.def.isCalleeOnly }.associate { callee ->
            val base = callee.def.baseId
            // usedSkills is the closure of what a skill calls (through parts and other skills too), so every skill that reaches the callee is named
            callee.def.id to skills.values.filter { c -> c.def.baseId != base && c.usedSkills.any { it.substringBefore('@') == base } }
                .map { it.def.baseId }.distinct().sorted()
        }
    }

    @Synchronized
    override fun reload(composed: List<ComposedSkill>): SkillLoadReport {
        val failed = LinkedHashMap<String, SkillDecodeError>()
        val changedParts = LinkedHashSet<String>()
        for (c in composed.filter { it.isPart }) {
            try {
                partOverlay.put(PartParser.part(c.tree, "part:${c.id}"))
                changedParts += c.id
            } catch (e: PartAbort) {
                failed[c.key] = SkillDecodeError.Invalid(c.id, e.error.path, e.error.message)
            }
        }
        val env = GrammarEnv(partOverlay.ids(), skills.keys + composed.filterNot { it.isPart }.map { it.id })
        val defs = LinkedHashMap<String, SkillDef>()
        for (c in composed.filterNot { it.isPart }) {
            when (val d = decoder.decode(c.id, c.tree, env)) {
                is Result.Success -> defs[c.id] = d.value
                is Result.Failure -> failed[c.id] = d.error
            }
        }
        val batch = MapSkillCatalogView(defs.values)
        val view = object : SkillCatalogView {
            override fun skill(id: String): SkillDef? = batch.skill(id) ?: skills[id]?.def
        }
        val kinds = composed.filterNot { it.isPart }.associate { it.id to it.patch.kind }
        val ok = LinkedHashMap<String, CompiledSkill>()
        for ((id, def) in defs) {
            when (val r = compiler.compile(def, partOverlay, view)) {
                is Result.Success -> ok[id] = withUses(r.value, view).copy(patchKind = kinds[id] ?: "base")
                is Result.Failure -> failed[id] = r.error
            }
        }
        // check 14: a skin variant plays its base's gameplay; one that does not never enters the catalog (the previous version, if any, stays)
        for (c in composed) {
            if (c.isPart || '@' !in c.id || c.patch.kind != "skin") continue
            val variant = ok[c.id] ?: continue
            val baseId = c.id.substringBefore('@')
            if (baseId in failed) continue
            val base = ok[baseId] ?: skills[baseId] ?: continue
            SkinGameplayParityCheck.run(base, variant, c.id)?.let { failed[c.id] = it; ok.remove(c.id) }
        }
        skills.putAll(ok)
        val loaded = ArrayList(ok.keys)
        loaded += recompileDependents(changedParts, ok.keys, ok.keys, failed)
        markCalleeOnly()
        return SkillLoadReport(loaded, failed)
    }

    @Synchronized
    override fun remove(skillId: String) {
        if (skillId.startsWith(PART_KEY)) {
            val partId = skillId.removePrefix(PART_KEY)
            partOverlay.remove(partId)
            recompileDependents(setOf(partId), emptySet(), emptySet(), LinkedHashMap())
            markCalleeOnly()
            return
        }
        skills.remove(skillId)
        markCalleeOnly()
    }

    /**
     * Recompiles, to a fixed point, every skill whose `usedParts` meets [parts] or whose `usedSkills` meets [changedSkills]
     * (skills already recompiled in this batch are not redone). A recompile that fails keeps the old [CompiledSkill] and is
     * reported in [failed]; the failure does not propagate to its own callers.
     */
    private fun recompileDependents(
        parts: Set<String>,
        changedSkills: Set<String>,
        batch: Set<String>,
        failed: MutableMap<String, SkillDecodeError>,
    ): List<String> {
        val view = asView()
        val done = LinkedHashSet<String>()
        var frontier = parts
        var frontierSkills: Set<String> = LinkedHashSet(changedSkills)
        while (true) {
            val hit = skills.entries
                .filter { (id, c) -> id !in batch && id !in done && (c.usedParts.any { it in frontier } || c.usedSkills.any { it in frontierSkills }) }
                .map { it.key to it.value.def }
            if (hit.isEmpty()) return done.toList()
            val next = LinkedHashSet<String>()
            for ((id, def) in hit) {
                done += id
                when (val r = compiler.compile(def, partOverlay, view)) {
                    is Result.Success -> {
                        skills[id] = withUses(r.value, view).copy(patchKind = skills[id]?.patchKind ?: "base")
                        next += id
                    }
                    is Result.Failure -> failed[id] = r.error
                }
            }
            frontier = emptySet()
            frontierSkills = next
        }
    }

    /** [compiled] with `usedParts` / `usedSkills` set to the closure of what its def calls, merged with what the compiler reported. */
    private fun withUses(compiled: CompiledSkill, view: SkillCatalogView): CompiledSkill {
        val parts = LinkedHashSet(compiled.usedParts)
        val calls = LinkedHashSet(compiled.usedSkills)
        val seenSkills = HashSet<String>().apply { add(compiled.def.id) }
        val seenParts = HashSet<String>()
        val partQueue = ArrayDeque<String>()
        val skillQueue = ArrayDeque<String>()
        fun scan(m: com.astarworks.astera.core.domain.skillengine.part.Machine) {
            val p = LinkedHashSet<String>()
            val s = LinkedHashSet<String>()
            UseCollector.direct(m, p, s)
            parts += p
            calls += s
            p.filter { seenParts.add(it) }.forEach { partQueue += it }
            s.filter { seenSkills.add(it) }.forEach { skillQueue += it }
        }
        scan(compiled.def.machine)
        parts.forEach { if (seenParts.add(it)) partQueue += it }
        calls.forEach { if (seenSkills.add(it)) skillQueue += it }
        while (partQueue.isNotEmpty() || skillQueue.isNotEmpty()) {
            if (partQueue.isNotEmpty()) partOverlay.part(partQueue.removeFirst())?.let { scan(it.machine) }
            else view.skill(skillQueue.removeFirst())?.let { scan(it.machine) }
        }
        return compiled.copy(usedParts = parts, usedSkills = calls)
    }

    private companion object {
        const val PART_KEY = "part:"
    }
}
