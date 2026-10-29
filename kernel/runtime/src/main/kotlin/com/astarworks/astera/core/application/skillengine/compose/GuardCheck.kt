package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.expr.cel.CondBindings
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.expr.ExprCompiler
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.part.Machine
import com.astarworks.astera.core.domain.skillengine.part.MachineTransition

/**
 * Load-time check of every machine transition `when:` (ADR-0303 3.8): the guard is compiled as bool against the bindings that transition
 * can see, so a wrong arity, an unknown function or an unbound name is a load error naming the state, the transition and the expression,
 * never a failure in the middle of a fight. `event.attacker` is bound only on a `hurt` transition.
 */
internal object GuardCheck {
    private const val ATTACKER = "event.attacker"

    fun check(
        skillId: String,
        machine: Machine,
        conditions: Map<String, String>,
        exprs: ExprCompiler,
        env: ExprEnvSpec,
    ): Result<Unit, SkillDecodeError> {
        fun one(path: String, t: MachineTransition): SkillDecodeError? {
            val w = t.whenExpr ?: return null
            val hurt = t.event == "hurt" || t.event?.startsWith("hurt.") == true
            val scoped = if (hurt) env else ExprEnvSpec(env.variables - ATTACKER, env.frames)
            val src = w.trim().removePrefix("=").trim()
            val expanded = when (val r = CondBindings.expand(skillId, path, src, conditions)) {
                is Result.Success -> r.value
                is Result.Failure -> return r.error
            }
            return when (val c = exprs.compile(expanded, scoped, ExprType.BOOL)) {
                is Result.Success -> null
                is Result.Failure -> SkillDecodeError.Invalid(skillId, path, "$path: guard '$w' does not compile: ${c.error.message}")
            }
        }
        fun walk(m: Machine, prefix: String): SkillDecodeError? {
            for ((sid, s) in m.states) {
                s.on.forEachIndexed { k, t -> one("${prefix}state '$sid' on[$k]" + (t.event?.let { " event '$it'" } ?: ""), t)?.let { return it } }
                s.parallel.forEach { r -> walk(r, "${prefix}state '$sid' region: ")?.let { return it } }
            }
            m.links.forEach { l -> one("${prefix}link '${l.callPath}' event '${l.event}'", l.transition)?.let { return it } }
            return null
        }
        return walk(machine, "")?.let { Result.Failure(it) } ?: Result.Success(Unit)
    }
}
