package com.astarworks.astera.core.application.skillengine.load.grammar

import com.astarworks.astera.core.application.skillengine.load.DecodeAbort
import com.astarworks.astera.core.application.skillengine.load.OpListDecoder
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.ValueDecoding
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.expr.ExprCompiler
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.model.ParamValue
import com.astarworks.astera.core.domain.skillengine.model.Value

/**
 * What the shared value grammar (ADR-0303 sections 0, 2.0, 3, 11) needs to read one file.
 *
 * Value forms are NOT re-implemented here: `${param}` / `"= <CEL>"` / literal are parsed by
 * [ValueDecoding] (load/ValueDecoding.kt, F-7) into the domain [Value]; CEL is compiled by the existing
 * `CelExprCompiler` (expr/cel/CelExprCompiler.kt) through the [ExprCompiler] port; `${param}` names are the
 * keys of `params:` ([ParamValue]), resolved at run time by `ValueResolver`.
 *
 * @property file    used as `skillId` of every [SkillDecodeError], so each error names the file.
 * @property params  declared `params:` of the skill; when non-null a `${name}` outside it is an
 *                   [SkillDecodeError.UndefinedReference]. Null = do not check.
 * @property exprs   when non-null every `= expr` is compiled against [env] at load and a bad one is a
 *                   [SkillDecodeError.BadExpr]. Null = defer to `DefaultSkillCompiler`.
 */
public class GrammarContext(
    public val file: String,
    public val params: Map<String, ParamValue>? = null,
    public val exprs: ExprCompiler? = null,
    public val env: ExprEnvSpec = ExprEnvSpec(),
    /** The decoder that nested op lists (a group's `do:`, a projectile's `on:`) resolve through; null outside an op-list decode. */
    public val opLists: OpListDecoder? = null,
) {
    /** The same context with [decoder] as the nested-list decoder. */
    public fun withOpLists(decoder: OpListDecoder): GrammarContext = GrammarContext(file, params, exprs, env, decoder)

    /** Aborts the current [Grammar.guard] block with a [SkillDecodeError.Invalid] at [path]. */
    public fun fail(path: String, message: String): Nothing =
        throw DecodeAbort(SkillDecodeError.Invalid(file, path, message))

    public fun mismatch(path: String, expected: String, raw: Any?): Nothing =
        ValueDecoding.mismatch(file, path, expected, raw)

    internal fun abort(error: SkillDecodeError): Nothing = throw DecodeAbort(error)

    /** Validates a decoded [Value] (param declared / CEL compiles to [expected]) and returns it. */
    public fun <T> checked(value: Value<T>, expected: ExprType, path: String): Value<T> {
        when (value) {
            is Value.ParamRef -> {
                val declared = params
                if (declared != null && value.name !in declared) {
                    abort(SkillDecodeError.UndefinedReference(file, path, "\${${value.name}}"))
                }
            }
            is Value.Expr -> {
                val compiler = exprs
                if (compiler != null) {
                    when (val r = compiler.compile(value.source, env, expected)) {
                        is Result.Success -> Unit
                        is Result.Failure -> abort(SkillDecodeError.BadExpr(file, path, r.error))
                    }
                }
            }
            is Value.Literal -> Unit
        }
        return value
    }

    /** Load-time resolution of a number: a literal, or a `${param}` with a numeric declared value; null for CEL / unknown. */
    public fun resolveNumber(value: Value<Double>): Double? = when (value) {
        is Value.Literal -> value.value
        is Value.ParamRef -> (params?.get(value.name) as? ParamValue.Num)?.value
        is Value.Expr -> null
    }
}

public object Grammar {
    /** Runs a grammar read; every load error of the block comes back as a value (application API rule: no throwables). */
    public fun <T> guard(block: () -> T): Result<T, SkillDecodeError> =
        try {
            Result.Success(block())
        } catch (e: DecodeAbort) {
            Result.Failure(e.error)
        }
}
