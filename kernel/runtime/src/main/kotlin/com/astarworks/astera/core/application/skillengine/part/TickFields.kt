package com.astarworks.astera.core.application.skillengine.part

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.skillengine.part.Machine

/**
 * The one rule for a machine timer (a state's `for`, a transition's `after`, a `seq` wait; ADR-0303 rev4.10): a non-negative integer of ticks,
 * a CEL string (judged where it is lowered) or `"${param}"`, which `ParamSubst` folds to the param's value (default, call argument or patched
 * default) before the machine is expanded, so the expansion table only ever sees an integer. A `${param}` that survives folding names an
 * undeclared param.
 */
internal object TickFields {
    private val PARAM = Regex("""^\$\{[A-Za-z_][A-Za-z0-9_]*}$""")

    fun isParamRef(raw: Any?): Boolean = raw is String && PARAM.matches(raw)

    private fun isCel(raw: Any?): Boolean = raw is String && raw.startsWith("= ")

    fun isWholeTicks(raw: Any?): Boolean =
        raw is Number && raw.toDouble() >= 0 && raw.toDouble() == Math.floor(raw.toDouble()) && !raw.toDouble().isInfinite()

    /** Parse time: what a document may write; the value is checked again by [checkFolded] after the params are substituted. */
    fun parseable(raw: Any?): Boolean = isWholeTicks(raw) || isCel(raw) || isParamRef(raw)

    const val EXPECTED: String = "a non-negative integer of ticks, \"\${param}\" or a CEL string"

    /** After folding: every timer of [m] (and of its parallel regions) is a whole tick count or a CEL string; otherwise a load error naming the field. */
    fun checkFolded(skillId: String, m: Machine, path: String): SkillDecodeError? {
        for (s in m.states.values) {
            val at = "$path.states.${s.id.replace("/", ".states.")}"
            problem(skillId, "$at.for", s.forTicks)?.let { return it }
            for ((i, t) in s.on.withIndex()) problem(skillId, "$at.on[$i].after", t.afterTicks)?.let { return it }
            for ((i, r) in s.parallel.withIndex()) checkFolded(skillId, r, "$at.parallel[$i]")?.let { return it }
        }
        return null
    }

    private fun problem(skillId: String, at: String, v: Any?): SkillDecodeError? = when {
        v == null || isWholeTicks(v) || isCel(v) -> null
        isParamRef(v) -> SkillDecodeError.UndefinedReference(skillId, at, "$v (no such param with a default)")
        else -> SkillDecodeError.Invalid(skillId, at, "must be $EXPECTED after folding params, got '$v'")
    }
}
