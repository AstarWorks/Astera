package com.astarworks.astera.core.application.skillengine.expr.cel

import com.astarworks.astera.core.application.skillengine.compose.ExprClass
import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.expr.CompiledExpr
import com.astarworks.astera.core.domain.skillengine.expr.ExprCompiler
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec
import com.astarworks.astera.core.domain.skillengine.expr.ExprType

/**
 * Named conditions (ADR-0303 3.8). `cond.<name>` inside a CEL source is replaced by the parenthesised body of that
 * condition BEFORE the source reaches the CEL compiler, so a condition is typed where it is declared and its S/T/L class
 * (3.5) is derived from the expanded expression by the ordinary classifier. No CEL variable named `cond` exists.
 */
public object CondBindings {
    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val REF = Regex("(?<![A-Za-z0-9_.])cond\\.([A-Za-z_][A-Za-z0-9_]*)")
    private val WHEN_PREFIX = "cond:"

    /** True when [name] is spelled as an identifier (a `conditions:` key). */
    public fun isValidName(name: String): Boolean = NAME.matches(name)

    /** Names referenced as `cond.<name>` in [source], in order of appearance. */
    public fun referencedNames(source: String): List<String> = REF.findAll(source).map { it.groupValues[1] }.toList()

    /**
     * Replaces every `cond.<name>` in [source] by `(<body>)` recursively. Unknown name = [SkillDecodeError.UnknownCondition];
     * a condition that reaches itself = [SkillDecodeError.Invalid].
     */
    public fun expand(
        skillId: String,
        path: String,
        source: String,
        conditions: Map<String, String>,
    ): Result<String, SkillDecodeError> = expandRec(skillId, path, source, conditions, emptyList())

    private fun expandRec(
        skillId: String,
        path: String,
        source: String,
        conditions: Map<String, String>,
        stack: List<String>,
    ): Result<String, SkillDecodeError> {
        val sb = StringBuilder()
        var last = 0
        for (m in REF.findAll(source)) {
            val name = m.groupValues[1]
            val body = conditions[name]
                ?: return Result.Failure(SkillDecodeError.UnknownCondition(path, name, skillId))
            if (name in stack) {
                return Result.Failure(
                    SkillDecodeError.Invalid(skillId, path, "condition '$name' refers to itself (${(stack + name).joinToString(" -> ")})"),
                )
            }
            val inner = when (val r = expandRec(skillId, "$path/conditions.$name", stripEq(body), conditions, stack + name)) {
                is Result.Success -> r.value
                is Result.Failure -> return r
            }
            sb.append(source, last, m.range.first).append('(').append(inner).append(')')
            last = m.range.last + 1
        }
        sb.append(source, last, source.length)
        return Result.Success(sb.toString())
    }

    /** Compiles every declared condition (bool result) against [env]. The key order of [conditions] is kept. */
    public fun compileAll(
        skillId: String,
        conditions: Map<String, String>,
        exprs: ExprCompiler,
        env: ExprEnvSpec,
    ): Result<Map<String, CompiledExpr>, SkillDecodeError> {
        val out = LinkedHashMap<String, CompiledExpr>()
        for ((name, body) in conditions) {
            val path = "conditions.$name"
            if (!isValidName(name)) {
                return Result.Failure(SkillDecodeError.Invalid(skillId, path, "condition name '$name' is not an identifier"))
            }
            val expanded = when (val r = expand(skillId, path, stripEq(body), conditions)) {
                is Result.Success -> r.value
                is Result.Failure -> return r
            }
            when (val c = exprs.compile(expanded, env, ExprType.BOOL)) {
                is Result.Success -> out[name] = c.value
                is Result.Failure -> return Result.Failure(SkillDecodeError.BadExpr(skillId, path, c.error))
            }
        }
        return Result.Success(out)
    }

    /**
     * Reads a `when:` string. `cond:<name>` gives [WhenRef.Named] (name checked against [conditions]); any other string
     * is a CEL expression: `cond.<name>` is expanded and the result compiled as bool.
     */
    public fun whenRef(
        skillId: String,
        path: String,
        text: String,
        conditions: Map<String, String>,
        exprs: ExprCompiler,
        env: ExprEnvSpec,
    ): Result<WhenRef, SkillDecodeError> {
        val t = text.trim()
        if (t.startsWith(WHEN_PREFIX)) {
            val name = t.removePrefix(WHEN_PREFIX).trim()
            if (name !in conditions) return Result.Failure(SkillDecodeError.UnknownCondition(path, name, skillId))
            return Result.Success(WhenRef.Named(name))
        }
        val expanded = when (val r = expand(skillId, path, stripEq(t), conditions)) {
            is Result.Success -> r.value
            is Result.Failure -> return r
        }
        return when (val c = exprs.compile(expanded, env, ExprType.BOOL)) {
            is Result.Success -> Result.Success(WhenRef.Expr(c.value))
            is Result.Failure -> Result.Failure(SkillDecodeError.BadExpr(skillId, path, c.error))
        }
    }

    /** The compiled guard behind a [WhenRef] at lowering time. */
    public fun guard(path: String, ref: WhenRef, lc: LowerContext): Result<CompiledExpr, SkillDecodeError> = when (ref) {
        is WhenRef.Expr -> Result.Success(ref.compiled)
        is WhenRef.Named -> lc.conditions[ref.cond]?.let { Result.Success(it) }
            ?: Result.Failure(SkillDecodeError.UnknownCondition(path, ref.cond, lc.skillId))
    }

    /** S / T / L class of a declared condition (3.5), derived from its expanded body. */
    public fun classOf(path: String, name: String, lc: LowerContext): Result<ExprClass, SkillDecodeError> =
        lc.conditions[name]?.let { Result.Success(lc.classify(it)) }
            ?: Result.Failure(SkillDecodeError.UnknownCondition(path, name, lc.skillId))

    private fun stripEq(s: String): String = s.trim().removePrefix("=").trim()
}
