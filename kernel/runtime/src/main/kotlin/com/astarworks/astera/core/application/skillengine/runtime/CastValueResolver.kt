package com.astarworks.astera.core.application.skillengine.runtime

import com.astarworks.astera.core.application.skillengine.load.CompiledSkill
import com.astarworks.astera.core.application.skillengine.load.SkillExprSpec
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.expr.CompiledExpr
import com.astarworks.astera.core.domain.skillengine.expr.ExprCompiler
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.model.ParamValue
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver
import java.util.WeakHashMap

/** A value could not be resolved at run time; the op that asked is reported as failed, the cast goes on. */
internal class ValueResolveException(message: String) : IllegalStateException(message)

/**
 * Expressions compiled once per [CompiledSkill] (a skill reload swaps in a new object, so old casts keep their
 * cache and new casts compile fresh). `Value.Expr` only carries source text, so the cache is keyed by source; the
 * compiler's own results ([CompiledSkill.bySource]) are used first, the rest compiles on demand against the same
 * [SkillExprSpec].
 */
internal class ExprBank(private val compiler: ExprCompiler) {
    private class Holder(val spec: SkillExprSpec, val cache: MutableMap<String, CompiledExpr>)

    private val perSkill = WeakHashMap<CompiledSkill, Holder>()

    @Synchronized
    private fun holder(skill: CompiledSkill): Holder =
        perSkill.getOrPut(skill) { Holder(SkillExprSpec.of(skill.def), HashMap(skill.bySource)) }

    fun compile(skill: CompiledSkill, source: String): CompiledExpr {
        val h = holder(skill)
        synchronized(h.cache) {
            h.cache[source]?.let { return it }
            return when (val r = compiler.compile(h.spec.rewrite(source), h.spec.spec)) {
                is Result.Success -> r.value.also { h.cache[source] = it }
                is Result.Failure -> throw ValueResolveException("expression '$source': ${r.error.message}")
            }
        }
    }
}

/**
 * [ValueResolver] of one cast (F-7): literal as is; `${param}` from the cast's params after modifiers
 * (F-12 snapshot taken at start); `= expr` through the compiled CEL expression and the cast's [CastEnv].
 */
internal class CastValueResolver(
    private val cast: CastInstance,
    private val bank: ExprBank,
) : ValueResolver {

    override fun num(value: Value<Double>): Double = when (value) {
        is Value.Literal -> value.value
        is Value.ParamRef -> (param(value.name) as? ParamValue.Num)?.value
            ?: throw ValueResolveException("param '${value.name}' is not a number")
        is Value.Expr -> when (val r = eval(value.source)) {
            is ExprValue.Num -> r.value
            else -> throw ValueResolveException("'${value.source}' is ${r.kind()}, expected number")
        }
    }

    override fun vec3(value: Value<Vec3>): Vec3 = when (value) {
        is Value.Literal -> value.value
        is Value.ParamRef -> throw ValueResolveException("param '${value.name}' cannot be a vector")
        is Value.Expr -> when (val r = eval(value.source)) {
            is ExprValue.Vec3 -> r.value
            else -> throw ValueResolveException("'${value.source}' is ${r.kind()}, expected vec3")
        }
    }

    override fun quat(value: Value<Quaternion>): Quaternion = when (value) {
        is Value.Literal -> value.value
        is Value.ParamRef -> throw ValueResolveException("param '${value.name}' cannot be a quaternion")
        is Value.Expr -> when (val r = eval(value.source)) {
            is ExprValue.Quat -> r.value
            else -> throw ValueResolveException("'${value.source}' is ${r.kind()}, expected quat")
        }
    }

    override fun bool(value: Value<Boolean>): Boolean = when (value) {
        is Value.Literal -> value.value
        is Value.ParamRef -> (param(value.name) as? ParamValue.Flag)?.value
            ?: throw ValueResolveException("param '${value.name}' is not a bool")
        is Value.Expr -> when (val r = eval(value.source)) {
            is ExprValue.Bool -> r.value
            else -> throw ValueResolveException("'${value.source}' is ${r.kind()}, expected bool")
        }
    }

    override fun text(value: Value<String>): String = when (value) {
        is Value.Literal -> value.value
        is Value.ParamRef -> when (val p = param(value.name)) {
            is ParamValue.Text -> p.value
            is ParamValue.Num -> p.value.toString()
            is ParamValue.Flag -> p.value.toString()
        }
        is Value.Expr -> when (val r = eval(value.source)) {
            is ExprValue.Str -> r.value
            is ExprValue.Ref -> r.name
            else -> throw ValueResolveException("'${value.source}' is ${r.kind()}, expected string")
        }
    }

    private fun param(name: String): ParamValue =
        cast.params[name] ?: throw ValueResolveException("unknown param '$name'")

    private fun eval(source: String): ExprValue = bank.compile(cast.skill, source).eval(cast.exprEnv())

    private fun ExprValue.kind(): String = when (this) {
        is ExprValue.Num -> "number"
        is ExprValue.Vec3 -> "vec3"
        is ExprValue.Quat -> "quat"
        is ExprValue.Bool -> "bool"
        is ExprValue.Str -> "string"
        is ExprValue.Ref -> "ref"
    }
}
