package com.astarworks.astera.core.application.skillengine.load.ops

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.compose.Occurrence
import com.astarworks.astera.core.application.skillengine.expr.cel.ExprClassifier
import com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec
import com.astarworks.astera.core.domain.skillengine.expr.ExprEvalException
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.expr.FrameLookup
import com.astarworks.astera.core.domain.geometry.math.Frame

/**
 * Ties a CEL source to ONE occurrence of a `times` / `every` op (ADR-0303 2.0a). The run-time evaluator of a `Val.Live` cell does
 * not know the copy it belongs to (`i` reads 0 there), so everything that makes copies differ is written into the source text:
 *  - `i`, `n`, `parent.i`, `parent.n` become the occurrence's numbers;
 *  - the stream name of `roll(..)` / `roll_unit(..)` / `rand(name)` gets the occurrence chain as a suffix and a bare `rand()` becomes
 *    a numbered, suffixed stream, so copies draw different values (R10) while one copy replays the same values; `rand(name, k)`
 *    (the k-th draw of a per-cast stream) is shared by the copies;
 *  - `draw(..)` / `draw_unit(..)` are left alone: they are per cast, the same for every copy and tick (ring phase).
 */
internal object OccurrenceBake {
    private val STRING = Regex("""'(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*"""")
    private val OCC_VAR = Regex("""(?<![A-Za-z0-9_.])((?:parent\.)*)([in])(?![A-Za-z0-9_(.])""")
    private val BARE_RAND = Regex("""(?<![A-Za-z0-9_.])rand\s*\(\s*\)""")
    private val ROLL_NAME = Regex("""(?<![A-Za-z0-9_.])(roll_unit|roll)\s*\(\s*(['"])((?:[^'"\\]|\\.)*)\2""")
    private val RAND_NAME = Regex("""(?<![A-Za-z0-9_.])(rand)\s*\(\s*(['"])((?:[^'"\\]|\\.)*)\2(?=\s*\))""")

    fun bake(source: String, occ: Occurrence): String {
        val chain = chain(occ)
        var randCount = 0
        val sb = StringBuilder()
        var last = 0
        fun code(seg: String): String {
            val noRand = BARE_RAND.replace(seg) { "rand(\"@${randCount++}\")" }
            return OCC_VAR.replace(noRand) { m ->
                var o: Occurrence? = occ
                repeat(m.groupValues[1].count { it == '.' }) { o = o?.parent }
                val at = o ?: return@replace m.value
                (if (m.groupValues[2] == "i") at.i else at.n).toString() + ".0"
            }
        }
        for (s in STRING.findAll(source)) {
            sb.append(code(source.substring(last, s.range.first))).append(s.value)
            last = s.range.last + 1
        }
        sb.append(code(source.substring(last)))
        val suffix = { m: MatchResult -> "${m.groupValues[1]}(${m.groupValues[2]}${m.groupValues[3]}#$chain${m.groupValues[2]}" }
        return RAND_NAME.replace(ROLL_NAME.replace(sb.toString(), suffix), suffix)
    }

    private fun chain(occ: Occurrence): String {
        val parts = ArrayList<Int>()
        var o: Occurrence? = occ
        while (o != null) {
            parts += o.i
            o = o.parent
        }
        return parts.joinToString(".")
    }

    private val noFrames = object : FrameLookup {
        override fun worldPose(frame: String, point: String?): Frame? = null
        override fun velocity(frame: String, point: String?): Vec3 = Vec3.ZERO
    }

    /**
     * The value of an expression that depends on the occurrence only (no random stream, time, live entity or runtime position),
     * else null: the cell stays a `Val.Live` with the baked source. A compile error is returned as null too; the grammar already
     * compiled the source once at decode, so a failure here means it needs a frame the lowering cannot see.
     */
    fun fold(lc: LowerContext, source: String, expected: ExprType): ExprValue? {
        val a = ExprClassifier.analyse(source) { lc.anchors.resolve(it)?.basis }
        if (a.usesRand || a.usesTime || a.usesLive || a.usesRuntimePosition) return null
        val compiled = when (val r = lc.exprs.compile(source, V3Bindings.spec(lc.occurrence, ExprEnvSpec()), expected)) {
            is Result.Success -> r.value
            is Result.Failure -> return null
        }
        return try {
            compiled.eval(ExprEnv(V3Bindings.values(lc.occurrence), noFrames))
        } catch (_: ExprEvalException) {
            null
        }
    }
}
