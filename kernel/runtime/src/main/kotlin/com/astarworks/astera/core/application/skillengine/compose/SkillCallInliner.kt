package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.SkillCatalogView
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.ops.control.UseOp
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.part.Machine
import com.astarworks.astera.core.domain.skillengine.part.Part
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Callee

/**
 * ADR-0303 section 9, "Skill to skill": `use: skill:<id>` calls a whole skill with the part mechanism. The callee is its
 * machine with its own `params`, `frames_in` and `emits`, so it is viewed as a [Part] and handed to [PartInliner]:
 *  - state-level calls (`use: skill:x` on a state) are renamed to the synthetic part id [partId] and inlined with the
 *    caller's states (ids prefixed with the call id); a skill call inside a called part or skill is rewritten the same way;
 *  - op-level calls ([lowerCall]) lower to the same marker `Call` row as a part call, with [Callee.SkillRef] carrying
 *    `gate:` (cooldown / cost are NOT applied to a called skill unless `gate:` asks for them).
 * A callee that declares `anchors`, `frames` or `conditions` of its own is a load error: a part sees only the frames
 * it was given, so those names could not be seen (take them through `frames_in`).
 * Skill-to-skill recursion is a load error (`skill call cycle`); nesting is bounded by `max_part_depth` in [PartInliner].
 */
public object SkillCallInliner {
    public const val PREFIX: String = "skill:"
    private const val PART_PREFIX = "skill/"

    /** Synthetic part id of a called skill, only visible to [PartInliner] and error messages. */
    public fun partId(skillId: String): String = PART_PREFIX + skillId

    /** The callee as a part: params, frames_in, emits and machine, no own anchors. */
    public fun asPart(callee: SkillDef, id: String = partId(callee.id)): Part =
        Part(id, callee.params, callee.framesIn, emptyMap(), callee.emits, callee.machine)

    /** Skill calls resolved, then parts inlined (the skill half of the pipeline step `PartInliner.inline`). */
    public fun inline(def: SkillDef, skills: SkillCatalogView, parts: PartCatalog, config: EngineConfig = EngineConfig()): Result<PartInliner.Inlined, SkillDecodeError> {
        val session = Session(def.id, skills, parts)
        val machine = session.rewrite(def.machine, "machine", listOf(def.id))
        session.error?.let { return Result.failure(it) }
        val out = PartInliner.inlineCounted(def.copy(machine = machine), session.catalog, config)
        session.error?.let { return Result.failure(it) }
        return out
    }

    /** Lowers an op-level `use: skill:<id>` via [lowerPart] (the part call lowering) on a catalog that serves the callee as a part. */
    internal fun lowerCall(
        op: UseOp,
        lc: LowerContext,
        lowerPart: (UseOp, LowerContext) -> Result<Lowered, SkillDecodeError>,
    ): Result<Lowered, SkillDecodeError> {
        val at = lc.src.value
        val skillId = op.target.removePrefix(PREFIX)
        val callee = lc.skills.skill(skillId) ?: return Result.failure(SkillDecodeError.UndefinedReference(lc.skillId, "$at.use", op.target))
        calleeProblem(lc.skillId, "$at.use", callee)?.let { return Result.failure(it) }
        val part = asPart(callee, op.target)
        val parts = object : PartCatalog {
            override fun part(id: String): Part? = if (id == op.target) part else lc.parts.part(id)
        }
        val viewed = LowerContext(
            lc.skillId, lc.state, lc.self, lc.occurrence, lc.clockOffset, lc.anchors, lc.frames, parts, lc.skills,
            lc.item, lc.sprites, lc.conditions, lc.exprs, lc.config, lc.seed, lc.src, hitScope = lc.hitScope, keys = lc.keys,
        )
        return lowerPart(op, viewed).map { lowered ->
            lowered.copy(rows = lowered.rows.map { row ->
                val a = row.action
                if (a is Action.Call) row.copy(action = a.copy(callee = Callee.SkillRef(skillId, op.gateCooldown, op.gateCost))) else row
            })
        }
    }

    private fun calleeProblem(skillId: String, at: String, callee: SkillDef): SkillDecodeError? = when {
        callee.anchors.isNotEmpty() -> SkillDecodeError.Invalid(skillId, at, "called skill '${callee.id}' declares anchors; a called skill sees only its frames_in")
        callee.fragments.isNotEmpty() -> SkillDecodeError.Invalid(skillId, at, "called skill '${callee.id}' declares fragments; fragments are local to the skill that declares them")
        callee.conditions.isNotEmpty() -> SkillDecodeError.Invalid(skillId, at, "called skill '${callee.id}' declares conditions; conditions are not carried into a caller")
        else -> null
    }

    private class Session(val rootId: String, val skills: SkillCatalogView, val parts: PartCatalog) {
        var error: SkillDecodeError? = null
        val skillParts = LinkedHashMap<String, Part>()
        private val partsDone = HashMap<String, Part>()

        val catalog = object : PartCatalog {
            override fun part(id: String): Part? = if (id.startsWith(PART_PREFIX)) skillParts[id] else rewritten(id)
        }

        private fun rewritten(id: String): Part? {
            partsDone[id]?.let { return it }
            val p = parts.part(id) ?: return null
            val out = p.copy(machine = rewrite(p.machine, "part:$id", emptyList()))
            partsDone[id] = out
            return out
        }

        fun rewrite(m: Machine, path: String, stack: List<String>): Machine {
            if (m.states.values.none { it.use?.part?.startsWith(PREFIX) == true }) return m
            return m.copy(states = m.states.mapValues { (_, s) ->
                val call = s.use
                if (call == null || !call.part.startsWith(PREFIX)) s
                else {
                    val id = resolve(call.part.removePrefix(PREFIX), "$path.states.${s.id}.use", call.part, stack)
                    if (id == null) s else s.copy(use = call.copy(part = id))
                }
            })
        }

        private fun resolve(skillId: String, at: String, written: String, stack: List<String>): String? {
            val pid = partId(skillId)
            if (pid in skillParts) return pid
            if (error != null) return null
            if (skillId in stack) {
                error = SkillDecodeError.Invalid(rootId, at, "skill call cycle: ${(stack.dropWhile { it != skillId } + skillId).joinToString(" -> ")}")
                return null
            }
            val callee = skills.skill(skillId)
            if (callee == null) {
                error = SkillDecodeError.UndefinedReference(rootId, at, written)
                return null
            }
            calleeProblem(rootId, at, callee)?.let { error = it; return null }
            val machine = rewrite(callee.machine, "skill:$skillId.machine", stack + skillId)
            if (error != null) return null
            skillParts[pid] = asPart(callee, pid).copy(machine = machine)
            return pid
        }
    }
}
