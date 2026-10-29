package com.astarworks.astera.core.application.skillengine.load.ops.presentation

import com.astarworks.astera.core.application.skillengine.compose.AnchorNames
import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.AnchorRef
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVec
import com.astarworks.astera.core.application.skillengine.load.ops.OccurrenceBake
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.table.SegmentSample
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.math.Vector3d

/**
 * Where one copy of a `particle` / `particle_def` is emitted (ADR-0303 2.2, R10, R14): the anchor point, displaced by the anchor's
 * `offset` (anchor axes) and by the op's `shift` (per copy; `{forward, up, left}` = anchor axes, a whole vec = world axes).
 * No displacement lowers to the bare anchor cell; otherwise to one `= expr` cell with the occurrence baked in ([OccurrenceBake]).
 */
internal object ParticlePlacement {
    class Placed(val position: Val<Vector3d>, val sweep: Double?, val sample: SegmentSample?)

    /**
     * Where a presentation anchor name points: [frame] is the CEL frame designator (`caster`, `point(caster, 'eye')`, an instance id),
     * [terms] the displacement already declared on a document anchor (anchor axes, summed), [live] the bare live name when nothing is displaced.
     */
    class Base(val frame: String, val terms: List<String>, val live: String)

    private val OFFSET_AXES = setOf("forward", "up", "left")

    /**
     * Resolves [name] for a sound / particle: a frame instance of the scope, a builtin (`caster`, `contact`, ... with an optional point), or a
     * document anchor of `anchors:` (ADR-0303 1; `follow` only, the chain of parents ends at a builtin or an instance). Null = not declared.
     */
    fun base(lc: LowerContext, name: String, op: String): Result<Base?, SkillDecodeError> {
        fun invalid(message: String): Result<Base?, SkillDecodeError> =
            Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value, "$op: $message"))
        AnchorNames.removedCasterPoint(name)?.let { return invalid(it) }
        val parts = AnchorNames.split(name) ?: return Result.Success(null)
        if (parts.index == null && parts.point == null) {
            val doc = lc.anchors.document(parts.base)
            if (doc != null) {
                // a sound / particle is a one-shot at its own tick: sampling the anchor then is exactly what `snapshot` means, so both modes place it alike
                val parent = when (val p = base(lc, doc.anchor.toString(), op)) {
                    is Result.Failure -> return p
                    is Result.Success -> p.value ?: return invalid("document anchor '${parts.base}' has parent '${doc.anchor}' which is not declared in this scope")
                }
                val terms = ArrayList(parent.terms)
                for ((axis, v) in doc.offset) {
                    if (axis !in OFFSET_AXES) return invalid("document anchor '${parts.base}' offset key '$axis' is not forward / up / left")
                    val src = componentSource(v) ?: return invalid("document anchor '${parts.base}' offset '$axis': a \${param} was not substituted before lowering")
                    if (src != "0.0") terms += "axis(${parent.frame}, '$axis') * $src"
                }
                return Result.Success(Base(parent.frame, terms, parent.live))
            }
        }
        AnchorNames.pointError(lc.anchors, name, op)?.let { return invalid(it) }
        // a frame a call bound (`frames_in`) is spelled by the caller's frame: the part's own name does not exist outside the part
        lc.anchors.resolve(name)?.let {
            val host = AnchorNames.host(lc.anchors, parts.base)
            return Result.Success(Base(parts.point?.let { p -> "point($host, '$p')" } ?: host, emptyList(), it.instance.text))
        }
        if (parts.index == null && parts.base in AnchorNames.BUILTIN_FRAMES) {
            val frame = parts.point?.let { "point(${parts.base}, '$it')" } ?: parts.base
            return Result.Success(Base(frame, emptyList(), parts.base))
        }
        return Result.Success(null)
    }

    private fun parts0(name: String) = AnchorNames.split(name)

    /** A position without a per-call displacement: the bare live name, or the anchor's own displacement / point as one `= expr` cell. */
    fun position(lc: LowerContext, name: String, op: String): Result<Val<Vector3d>, SkillDecodeError> {
        val b = when (val r = base(lc, name, op)) {
            is Result.Failure -> return r
            is Result.Success -> r.value ?: return Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value, "$op: anchor '$name' is not declared in this scope"))
        }
        if (b.terms.isEmpty() && b.frame == b.live) return Result.Success(Val.Live(b.live, null))
        if (b.terms.isEmpty() && b.frame == parts0(name)?.base && lc.anchors.resolve(name) != null) return Result.Success(Val.Live(b.live, null))
        val source = OccurrenceBake.bake((listOf("pos(${b.frame})") + b.terms).joinToString(" + "), lc.occurrence)
        return Result.Success(Val.Live("= $source", null))
    }

    /** One position (a world op acts once): `samples` is a particle-only rider. */
    fun place(op: String, lc: LowerContext, anchor: AnchorRef, shift: FrameVec?): Result<Placed, SkillDecodeError> {
        if (anchor.samples != null) {
            return Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value, "$op: anchor 'to' / 'samples' (a sampled segment) belongs to particle and particle_def only"))
        }
        return when (val r = placeAll(op, lc, anchor, shift)) {
            is Result.Failure -> r
            is Result.Success -> Result.Success(r.value.single())
        }
    }

    /**
     * The positions of one copy: one, or `samples` of them along the segment `of -> to` (k = 0..n-1 at `lerp(of, to, k / (n - 1))`, ADR-0303 1
     * rev4.8). `offset` / `shift` displace every sample alike (the segment moves as a whole). The compile-time expansion: n rows, no runtime loop.
     */
    fun placeAll(op: String, lc: LowerContext, anchor: AnchorRef, shift: FrameVec?): Result<List<Placed>, SkillDecodeError> {
        fun invalid(message: String): Result<List<Placed>, SkillDecodeError> =
            Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value, "$op: $message"))

        val resolved = when (val r = base(lc, anchor.of, op)) {
            is Result.Failure -> return r
            is Result.Success -> r.value ?: return invalid("anchor '${anchor.of}' is not declared in this scope")
        }
        val segment = anchor.to != null
        if (!segment && anchor.offset == null && shift == null) {
            val at = when (val r = position(lc, anchor.of, op)) {
                is Result.Failure -> return r
                is Result.Success -> r.value
            }
            return Result.Success(listOf(Placed(at, anchor.sweep, null)))
        }
        val parts = AnchorNames.split(anchor.of)
        if (parts == null || parts.index != null) {
            return invalid("anchor '${anchor.of}' is a family member; offset / shift / to need a plain anchor name (write the displacement on the family's own op)")
        }
        val frame = resolved.frame

        val displacement = ArrayList<String>()
        for (v in listOf(anchor.offset, (shift as? FrameVec.Parts))) {
            if (v is FrameVec.Parts) {
                for ((axis, c) in listOf("forward" to v.forward, "up" to v.up, "left" to v.left)) {
                    val src = componentSource(c) ?: return invalid("a \${param} in an offset / shift was not substituted before lowering")
                    if (src != "0.0") displacement += "axis($frame, '$axis') * $src"
                }
            }
        }
        if (shift is FrameVec.Whole) {
            val w = shift.value
            displacement += when (w) {
                is Value.Expr -> "(${w.source})"
                is Value.Literal -> "vec(${w.value.x}, ${w.value.y}, ${w.value.z})"
                is Value.ParamRef -> return invalid("a \${param} in an offset / shift was not substituted before lowering")
            }
        }
        fun end(b: Base) = (listOf("pos(${b.frame})") + b.terms).joinToString(" + ").let { if (b.terms.isEmpty()) it else "($it)" }

        if (!segment) {
            val source = OccurrenceBake.bake((listOf("pos($frame)") + resolved.terms + displacement).joinToString(" + "), lc.occurrence)
            return Result.Success(listOf(Placed(Val.Live("= $source", null), anchor.sweep, null)))
        }
        val toName = anchor.to!!
        val toParts = AnchorNames.split(toName)
        if (toParts == null || toParts.index != null) {
            return invalid("segment end 'to: $toName' is a family member; name a plain anchor")
        }
        val toBase = when (val r = base(lc, toName, op)) {
            is Result.Failure -> return r
            is Result.Success -> r.value ?: return invalid("anchor 'to: $toName' is not declared in this scope")
        }
        val n = anchor.samples!!
        val a = end(resolved)
        val b = end(toBase)
        val rows = (0 until n).map { k ->
            val t = k.toDouble() / (n - 1)
            val source = OccurrenceBake.bake((listOf("lerp($a, $b, $t)") + displacement).joinToString(" + "), lc.occurrence)
            Placed(Val.Live("= $source", null), anchor.sweep, SegmentSample(k, n))
        }
        return Result.Success(rows)
    }

    private fun componentSource(c: Value<Double>): String? = when (c) {
        is Value.Literal -> c.value.toString()
        is Value.Expr -> "(${c.source})"
        is Value.ParamRef -> null
    }
}
