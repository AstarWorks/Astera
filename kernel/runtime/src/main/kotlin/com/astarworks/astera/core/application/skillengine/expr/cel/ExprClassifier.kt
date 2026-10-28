package com.astarworks.astera.core.application.skillengine.expr.cel

import com.astarworks.astera.core.application.skillengine.compose.ExprClass
import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.expr.ExprEvalException
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.expr.FrameLookup
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.table.OpId

/**
 * S / T / L classification of an expression (ADR-0303 3.5), derived from the source text (a [com.astarworks.astera.core.domain.skillengine.expr.CompiledExpr]
 * keeps it): T when the source reads `t` / `lt` / `cast.t` / `state.t`; L when it queries a live entity at run time, i.e. a
 * frame function ([LIVE_FRAME_FUNCTIONS]) whose argument is `caster`, `target`, `victim`, `enemy` or an anchor of basis L, or a
 * world query without frame argument ([LIVE_WORLD_FUNCTIONS]); everything else (params, `i`, `n`, `rand()`, `contact.*`
 * and `caster.*` / `target.*` read at the occurrence start, snapshot anchors) is S. Strings are ignored.
 */
public object ExprClassifier {
    private val STRING = Regex("""'(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*"""")
    private val IDENT = Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")
    private val CALL = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
    private val RAND = Regex("""(?<![A-Za-z0-9_.])(?:rand|roll|roll_unit|draw|draw_unit)\s*\(""")

    private val TIME_VARS = setOf("t", "lt", "cast.t", "state.t")
    private val LIVE_FRAME_FUNCTIONS = setOf(
        "pos", "velocity", "speed", "axis", "forward", "up", "left", "right", "dist", "dist_xz", "in_cone", "behind", "hp_ratio", "enemy_in_cone", "stacks",
    )
    private val LIVE_WORLD_FUNCTIONS = setOf("aim_point", "look_dir", "nearest_enemy_dist", "enemy_in_cone")
    private val LIVE_ENTITIES = setOf("caster", "target", "victim", "enemy", "event")

    /** What the source reads, for the class and for check-9 messages. */
    public data class Analysis(
        val cls: ExprClass,
        val usesRand: Boolean,
        val usesTime: Boolean,
        val usesLive: Boolean,
        /** `contact.*` / `target.*` / `caster.*`: a position only known when the cast / hit happens (C4c). */
        val usesRuntimePosition: Boolean,
    )

    /** [basisOf] gives the basis class of an anchor name, null when [name] is not an anchor. */
    public fun analyse(source: String, basisOf: (String) -> ExprClass? = { null }): Analysis {
        val text = STRING.replace(source.trim().removePrefix("=")) { " ".repeat(it.value.length) }
        val idents = IDENT.findAll(text).map { it.value }.toList()
        val usesTime = idents.any { it in TIME_VARS }
        val usesRuntimePosition = idents.any { id -> id.contains('.') && id.substringBefore('.') in V3Bindings.RUNTIME_POSITION_ROOTS }
        val usesRand = RAND.containsMatchIn(text)
        var live = false
        for (m in CALL.findAll(text)) {
            val fn = m.groupValues[1]
            val args = argumentText(text, m.range.last + 1)
            if (fn in LIVE_WORLD_FUNCTIONS) live = true
            if (fn in LIVE_FRAME_FUNCTIONS) {
                for (a in IDENT.findAll(args).map { it.value }) {
                    val root = a.substringBefore('.')
                    if (root in LIVE_ENTITIES || basisOf(root) == ExprClass.L) live = true
                }
            }
        }
        // a bare anchor of basis L (e.g. `ball` used as a frame argument of a user-visible function) is covered above; nothing else is live.
        val cls = when {
            live -> ExprClass.L
            usesTime -> ExprClass.T
            else -> ExprClass.S
        }
        return Analysis(cls, usesRand, usesTime, live, usesRuntimePosition)
    }

    private fun argumentText(text: String, from: Int): String {
        var depth = 1
        var i = from
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return text.substring(from, (i - 1).coerceAtLeast(from))
    }

    /** Check 9 for a numeric timing field: a compile-time integer, or [SkillDecodeError.RuntimeTiming] / a decode error. */
    public fun staticTicks(lc: LowerContext, path: String, field: String, v: Value<Double>): Result<Int, SkillDecodeError> = when (v) {
        is Value.Literal -> finite(lc, path, field, v.value)
        is Value.ParamRef -> Result.Failure(
            SkillDecodeError.Invalid(lc.skillId, path, "timing field '$field': \${${v.name}} was not substituted by the part / skill call before lowering"),
        )
        is Value.Expr -> staticExpr(lc, path, field, v.source)
    }

    private fun finite(lc: LowerContext, path: String, field: String, d: Double): Result<Int, SkillDecodeError> =
        if (d.isNaN() || d.isInfinite() || Math.abs(d) > Int.MAX_VALUE) {
            Result.Failure(SkillDecodeError.Invalid(lc.skillId, path, "timing field '$field' is not a finite tick count ($d)"))
        } else Result.Success(Math.round(d).toInt())

    private fun staticExpr(lc: LowerContext, path: String, field: String, source: String): Result<Int, SkillDecodeError> {
        val spec = V3Bindings.spec(lc.occurrence)
        val compiled = when (val c = lc.exprs.compile(source, spec, com.astarworks.astera.core.domain.skillengine.expr.ExprType.NUM)) {
            is Result.Success -> c.value
            is Result.Failure -> return Result.Failure(SkillDecodeError.BadExpr(lc.skillId, path, c.error))
        }
        val a = analyse(source) { name -> lc.anchors.resolve(name)?.basis }
        val why = when {
            a.usesRand -> "rand / roll / draw are random per cast; a random delay is a value in a non-timing field, never a time (C5, R11)"
            a.usesTime -> "t / lt change every tick; timing is resolved to integers at compile time (C5)"
            a.usesLive -> "a live (class L) value, with or without a bound, cannot schedule anything; use until: / range: (C5)"
            a.usesRuntimePosition -> "contact / target / caster positions are only known at run time (C4c, C5)"
            else -> null
        }
        if (why != null) return Result.Failure(SkillDecodeError.RuntimeTiming(path, field, why, lc.skillId))
        val env = ExprEnv(V3Bindings.values(lc.occurrence), NoFrames, seed = V3Bindings.seed(0L, OpId(field), lc.occurrence.i))
        val r = try {
            compiled.eval(env)
        } catch (e: ExprEvalException) {
            return Result.Failure(SkillDecodeError.Invalid(lc.skillId, path, "timing field '$field': ${e.message}"))
        }
        val d = (r as? ExprValue.Num)?.value
            ?: return Result.Failure(SkillDecodeError.Invalid(lc.skillId, path, "timing field '$field' is not a number"))
        return finite(lc, path, field, d)
    }

    private object NoFrames : FrameLookup {
        override fun worldPose(frame: String, point: String?): Frame? = null
        override fun velocity(frame: String, point: String?): Vec3 = Vec3.ZERO
    }
}
