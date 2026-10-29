package com.astarworks.astera.core.application.skillengine.load.grammar

import com.astarworks.astera.core.application.skillengine.load.DecodeAbort
import com.astarworks.astera.core.application.skillengine.load.OpListDecoder
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.ValueDecoding
import com.astarworks.astera.core.application.skillengine.compose.Occurrence
import com.astarworks.astera.core.application.skillengine.expr.cel.ExprClassifier
import com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.expr.ExprEvalException
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.expr.FrameLookup
import com.astarworks.astera.core.domain.skillengine.expr.ExprCompiler
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.geometry.Vec3
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
    /**
     * The skill's declared `params:` defaults (null = unknown). A frame op's motion laws are lowered at load, so they read their `${param}` numbers
     * from here ([params] stays null so the other ops keep their run-time param refs).
     */
    public val paramDefaults: Map<String, ParamValue>? = null,
    /** `part -> long/edge points of that part` of the skill item's `content/frames/<item>.yaml`; null = no item frame table. */
    public val partAxes: ((String) -> Map<String, Vec3>?)? = null,
) {
    /** The same context with [decoder] as the nested-list decoder. */
    public fun withOpLists(decoder: OpListDecoder): GrammarContext = GrammarContext(file, params, exprs, env, decoder, paramDefaults, partAxes)

    /** The context a load-time law is read with: the skill's param defaults are known, and [axes] are the object's own (item part) axes. */
    public fun forLaws(axes: AxisResolver): GrammarContext =
        GrammarContext(file, paramDefaults ?: params, exprs, env, opLists, paramDefaults, partAxes).also { it.axes = axes }

    /** The copy ([Occurrence]) whose index `i` / `n` / `parent.i` a law number reads; null = this context has no copy index ([forCopy]). */
    public var occurrence: Occurrence? = null
        private set

    /** The same context bound to one copy of a family / repeat: a law number may then read `i`, `n` and the `parent.` chain (ADR-0303 3.5). */
    public fun forCopy(occurrence: Occurrence): GrammarContext =
        GrammarContext(file, params, exprs, env, opLists, paramDefaults, partAxes).also {
            it.axes = axes
            it.occurrence = occurrence
        }

    /** Axis names a rotation / law of this context can resolve; [LocalAxes] unless [forLaws] widened it. */
    public var axes: AxisResolver = LocalAxes
        private set

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
        is Value.Expr -> constant(value.source)
    }

    /**
     * A rate-law number written `= <CEL>` (ADR-0303 3.5): evaluated NOW, once, for the copy [occurrence] names, and baked as a constant into
     * the expansion table. It may read params, constants and the copy index (`i`, `n`, `parent.i`); a value that only exists at run time
     * (`t`, a frame or world function, `rand`) is a named load error, never a silent default.
     */
    public fun lawNumber(source: String, path: String): Double {
        val compiler = exprs ?: fail(path, "cannot evaluate '$source': this context has no expression compiler")
        val a = ExprClassifier.analyse(source)
        val where = "a law number is baked into the expansion table when the file loads, so it may read only constants, params and the copy index (i, n, parent.i)"
        if (a.usesLive) fail(path, "'$source' calls ${a.liveCall ?: "a live function"}, which is a runtime value (class L); $where")
        if (a.usesTime) fail(path, "'$source' reads t / lt, which change every tick; $where (write a change over time as 'accel' or a 'keys' law)")
        if (a.usesRand) fail(path, "'$source' uses rand / roll / draw; $where")
        if (a.usesRuntimePosition) fail(path, "'$source' reads a position that is only known at run time; $where")
        val occ = occurrence
        val spec = if (occ != null) V3Bindings.spec(occ, env) else ExprEnvSpec()
        val body = source.trim().removePrefix("=").trim()
        val compiled = when (val c = compiler.compile(body, spec, ExprType.NUM)) {
            is Result.Success -> c.value
            is Result.Failure -> {
                if (occ == null && COPY_INDEX.containsMatchIn(body)) {
                    fail(path, "'$source' reads the copy index (i / n), which this law context does not have: i in a law number is supported on a display's own motion (a `times:` family of displays), not on a frame or projectile body")
                }
                abort(SkillDecodeError.BadExpr(file, path, c.error))
            }
        }
        val noFrames = object : FrameLookup {
            override fun worldPose(frame: String, point: String?) = null
            override fun velocity(frame: String, point: String?) = Vec3.ZERO
        }
        val r = try {
            compiled.eval(ExprEnv(V3Bindings.values(occ), noFrames))
        } catch (e: ExprEvalException) {
            fail(path, "'$source' cannot be evaluated at load: ${e.message}")
        }
        val d = (r as? ExprValue.Num)?.value ?: fail(path, "'$source' is not a number")
        if (d.isNaN() || d.isInfinite()) fail(path, "'$source' is not a finite number ($d) for this copy")
        return d
    }

    /**
     * ADR-0303 3.5: a law number is class S when its expression names no variable, frame or random stream (a part's `= radius / ring_for` after
     * the call bound its params). It folds at load; anything that needs a runtime value stays null, i.e. the caller's "must be a literal" error.
     */
    private fun constant(source: String): Double? {
        val compiler = exprs ?: return null
        if (Regex("""\b(rand|draw)\s*\(""").containsMatchIn(source)) return null
        val compiled = (compiler.compile(source, ExprEnvSpec(), ExprType.NUM) as? Result.Success)?.value ?: return null
        val noFrames = object : com.astarworks.astera.core.domain.skillengine.expr.FrameLookup {
            override fun worldPose(frame: String, point: String?) = null
            override fun velocity(frame: String, point: String?) = com.astarworks.astera.core.domain.geometry.Vec3.ZERO
        }
        return try {
            (compiled.eval(com.astarworks.astera.core.domain.skillengine.expr.ExprEnv(emptyMap(), noFrames)) as? com.astarworks.astera.core.domain.skillengine.expr.ExprValue.Num)?.value
        } catch (e: com.astarworks.astera.core.domain.skillengine.expr.ExprEvalException) {
            null
        }
    }
}

private val COPY_INDEX = Regex("""\b(i|n|parent)\b""")

/** The declared defaults of a skill's `params:` as load-time values (a required param has none): what a frame's motion laws read `${param}` from. */
public fun loadTimeDefaults(params: Map<String, com.astarworks.astera.core.domain.skillengine.part.ParamSpec>): Map<String, ParamValue> =
    params.values.mapNotNull { p ->
        when (val d = p.default) {
            is Number -> p.name to ParamValue.Num(d.toDouble())
            is Boolean -> p.name to ParamValue.Flag(d)
            is String -> p.name to ParamValue.Text(d)
            else -> null
        }
    }.toMap()

public object Grammar {
    /** Runs a grammar read; every load error of the block comes back as a value (application API rule: no throwables). */
    public fun <T> guard(block: () -> T): Result<T, SkillDecodeError> =
        try {
            Result.Success(block())
        } catch (e: DecodeAbort) {
            Result.Failure(e.error)
        }
}
