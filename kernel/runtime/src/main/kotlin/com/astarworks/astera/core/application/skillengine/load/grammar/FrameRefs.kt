package com.astarworks.astera.core.application.skillengine.load.grammar

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.skillengine.frame.grammar.Coeff
import com.astarworks.astera.core.domain.skillengine.frame.grammar.FramePoint
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import com.astarworks.astera.core.domain.skillengine.table.Val

/**
 * `frame: <id>` + `pos: {v, w, s}` (ADR-0314; `at:` is the timing tick, so the coefficients are `pos:`): a draw op placed by coefficients of a declared frame's axes. The lowering writes one live
 * cell name [cell] that the runtime resolves from its per-tick frame table ([FrameRefs.PREFIX]); the compiler checks every such cell
 * names a declared frame ([FrameRefs.parse]).
 */
public data class FrameRef(val frame: String, val at: Coeff, val to: Coeff? = null, val samples: Int = 1, val direction: Boolean = false, val toFrame: String = frame, val u: Double = 0.0) {
    /** The live-cell name of `pos`: `frame:<id>#<v>,<w>,<s>`; of a direction (`dir`, no origin): `frame:<id>^<v>,<w>,<s>`. */
    public val cell: String get() = cellOf(at)

    public fun position(): Val.Live<Vector3d> = Val.Live(cell, null)

    /**
     * The sample points: `pos` alone, or `samples` points spread evenly from `pos` to `to` (both ends included) - a trail along the
     * blade, a line of sparks along `v`. Each is its own frame cell, so every point follows the frame per tick.
     */
    public fun points(): List<Val.Live<Vector3d>> {
        val end = to ?: return listOf(position())
        if (toFrame != frame) return (0 until samples).map { i -> Val.Live(crossCell(i.toDouble() / (samples - 1)), null) }
        if (samples <= 1) return listOf(Val.Live(cellOf(end), null))
        return (0 until samples).map { i ->
            val u = i.toDouble() / (samples - 1)
            Val.Live(cellOf(Coeff(at.v + (end.v - at.v) * u, at.w + (end.w - at.w) * u, at.s + (end.s - at.s) * u)), null)
        }
    }

    /** A point `u` of the way from `pos` on [frame] to `to` on [toFrame]: `frame:<a>#c|<b>#c@u`, the lerp of the two frames' world points. */
    private fun crossCell(u: Double): String = "${FrameRefs.PREFIX}$frame#${at.v},${at.w},${at.s}|$toFrame#${to!!.v},${to.w},${to.s}@$u"

    private fun cellOf(c: Coeff): String {
        val ex = c.exprs ?: return "${FrameRefs.PREFIX}$frame${if (direction) '^' else '#'}${c.v},${c.w},${c.s}"
        // an expression cell: `frame:<id>#=<v>;<w>;<s>`, each part a CEL source or a number (CEL has no `;`)
        return "${FrameRefs.PREFIX}$frame#=" + listOf(c.v, c.w, c.s).mapIndexed { i, n -> ex[i] ?: n.toString() }.joinToString(";")
    }

    /** This reference with every coefficient expression baked for the lowered occurrence (`i`, `n`, the `rand` stream). */
    internal fun baked(lc: com.astarworks.astera.core.application.skillengine.compose.LowerContext): FrameRef {
        val ex = at.exprs ?: return this
        val b = ex.map { it?.let { src -> com.astarworks.astera.core.application.skillengine.load.ops.OccurrenceBake.bake(src, lc.occurrence) } }
        return copy(at = at.copy(exprs = b))
    }
}

public object FrameRefs {
    public const val PREFIX: String = "frame:"

    private val coeffField = Field.optional("pos", Types.nullable(coeffType(allowExpr = true)), null, "coefficients {v, w, s} of the frame's axes (with frame:); absent = the origin")

    /** `frame: <id>` field: absent = the op uses `anchor:` instead. */
    public val FRAME: Field<String?> = Field.optional("frame", Types.nullable(Types.id), null, "a frame declared under frames: (ADR-0314); the op is placed by pos: {v, w, s} from its origin")

    /** `pos: {v, w, s}` field (with `frame:`). */
    public val POS: Field<Coeff?> = coeffField

    /** `to: {v, w, s}` field (with `frame:` + `samples:`): the far end of a segment of sample points. */
    public val TO: Field<Coeff?> = Field.optional("to", Types.nullable(coeffType()), null, "far end {v, w, s} of a segment from pos: (with frame: and samples:)")

    /** `dir: {v, w, s}` field (with `frame:`): a direction (no origin) along the frame's axes, for the ops that push or aim (knockback, pull, impulse). */
    public val DIR: Field<Coeff?> = Field.optional("dir", Types.nullable(coeffType()), null, "direction {v, w, s} along the frame's axes (with frame:)")

    /** `{frame, pos}` point (ADR-0314): the one point type of a ribbon end, an area target centre or a line end. */
    public val POINT: FieldType<FramePoint> = pointType()

    /** `samples: n` field (with `frame:` + `to:`, or `along:`): the number of points spread from `pos` to `to`, ends included. */
    public val SAMPLES: Field<Int?> = Field.optional("samples", Types.nullable(intType()), null, "points spread evenly from pos: to to: (or along a line), ends included (with frame: or along:)")

    /** `along: <line id>` field: the op emits at `samples:` points of a line declared under `lines:` (instead of frame: + pos: + to:). */
    public val ALONG: Field<String?> = Field.optional("along", Types.nullable(Types.id), null, "a line declared under lines: (ADR-0314); with samples: n the op emits at n points of it, ends included")

    private fun intType(): FieldType<Int> = object : FieldType<Int> {
        override val label = "integer"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): Int {
            val n = raw as? Number ?: ctx.mismatch(path, label, raw)
            if (n.toDouble() != n.toInt().toDouble()) ctx.mismatch(path, label, raw)
            return n.toInt()
        }
        override fun schema(): Map<String, Any?> = linkedMapOf("type" to "integer")
    }

    private fun pointType(): FieldType<FramePoint> = object : FieldType<FramePoint> {
        override val label = "{frame: <id>, pos: {v, w, s}}"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): FramePoint {
            val m = raw as? Map<*, *> ?: ctx.mismatch(path, label, raw)
            for (k in m.keys) if (k !in setOf("frame", "pos")) ctx.abort(SkillDecodeError.UnknownKey(ctx.file, path, k.toString(), "expected frame, pos"))
            val frame = Types.id.decode(m["frame"] ?: ctx.fail("$path.frame", "a frame point needs frame: <id>"), ctx, "$path.frame")
            val pos = m["pos"]?.let { coeffType().decode(it, ctx, "$path.pos") } ?: Coeff.ZERO
            return FramePoint(frame, pos)
        }
        override fun schema(): Map<String, Any?> = linkedMapOf(
            "type" to "object",
            "required" to listOf("frame"),
            "properties" to linkedMapOf("frame" to linkedMapOf("type" to "string"), "pos" to coeffType().schema()),
            "additionalProperties" to false,
        )
    }

    /** The live-cell name of [p] (a position): `frame:<id>#<v>,<w>,<s>`, read from the per-tick frame table like every `pos:`. */
    public fun cellOf(p: FramePoint): String = FrameRef(p.frame, p.pos).cell

    /** The live position cell of the frame point [p]. */
    public fun position(p: FramePoint): Val.Live<Vector3d> = Val.Live(cellOf(p), null)

    /** The live direction cell of `frame: [frame]` + `dir: [dir]`: the frame's axes weighted by the coefficients, no origin. */
    public fun directionCell(frame: String, dir: Coeff): String = FrameRef(frame, dir, direction = true).cell

    /** `{v, w, s}` coefficients of a frame's axes. */
    public val COEFF: FieldType<Coeff> get() = coeffType()

    private fun coeffType(allowExpr: Boolean = false): FieldType<Coeff> = object : FieldType<Coeff> {
        override val label = if (allowExpr) "{v, w, s} (a number or an '= expr' each)" else "{v, w, s}"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): Coeff {
            val m = raw as? Map<*, *> ?: ctx.mismatch(path, label, raw)
            for (k in m.keys) if (k !in setOf("v", "w", "s")) ctx.abort(SkillDecodeError.UnknownKey(ctx.file, path, k.toString(), "expected v, w, s"))
            val exprs = arrayOfNulls<String>(3)
            fun c(k: String, slot: Int): Double {
                val v = m[k] ?: return 0.0
                if (v is Number) return v.toDouble()
                if (allowExpr && v is String && v.startsWith("= ")) {
                    // compiled once here: a bad expression is a load error naming this path
                    val e = Types.number.decode(v, ctx, "$path.$k") as? com.astarworks.astera.core.domain.skillengine.model.Value.Expr
                        ?: ctx.mismatch("$path.$k", "number or '= expr'", v)
                    exprs[slot] = e.source
                    return 0.0
                }
                ctx.mismatch("$path.$k", if (allowExpr) "number or '= expr' (a CEL expression)" else "number (literal coefficient)", v)
            }
            val cv = c("v", 0); val cw = c("w", 1); val cs = c("s", 2)
            return Coeff(cv, cw, cs, if (exprs.any { it != null }) exprs.toList() else null)
        }
        override fun schema(): Map<String, Any?> = linkedMapOf(
            "type" to "object",
            "properties" to linkedMapOf("v" to linkedMapOf("type" to "number"), "w" to linkedMapOf("type" to "number"), "s" to linkedMapOf("type" to "number")),
            "additionalProperties" to false,
        )
    }

    /**
     * Reads the pair from decoded [values]: null when no `frame:`; a named load error when `pos:` is given without `frame:` or when
     * [anchorGiven] (the op also wrote an anchor) - an op is placed by exactly one of the two.
     */
    public fun read(values: FieldValues, ctx: GrammarContext, path: String, anchorGiven: Boolean): FrameRef? {
        val frame = values[FRAME]
        val pos = values[POS]
        if (frame == null) {
            if (pos != null) ctx.fail("$path.pos", "pos: {v, w, s} needs frame: <id>")
            return null
        }
        if (anchorGiven) ctx.fail("$path.frame", "frame: and anchor: are exclusive; a draw op is placed by one of them")
        return FrameRef(frame, pos ?: Coeff.ZERO)
    }

    /**
     * [read] plus the `to:` + `samples:` segment (a draw op that emits along a line of frame points), or `along: <line>` + `samples:`
     * (the same segment named once under `lines:`).
     */
    public fun readSegment(values: FieldValues, ctx: GrammarContext, path: String, anchorGiven: Boolean): FrameRef? {
        val along = values[ALONG]
        val to = values[TO]
        val samples = values[SAMPLES]
        if (along != null) {
            if (values[FRAME] != null || values[POS] != null || to != null) ctx.fail("$path.along", "along: names a line (its frame, from and to); frame:, pos: and to: are not written with it")
            if (anchorGiven) ctx.fail("$path.along", "along: and anchor: are exclusive; a draw op is placed by one of them")
            val line = ctx.lines?.get(along) ?: ctx.abort(SkillDecodeError.UndefinedReference(ctx.file, "$path.along", "line '$along' (not under lines:)"))
            val n = samples ?: ctx.fail("$path.samples", "along: needs samples: <n> (points from the line's from to its to, ends included)")
            if (n < 2) ctx.fail("$path.samples", "samples: is at least 2 (both ends of the line are drawn)")
            return FrameRef(line.from.frame, line.from.pos, line.to.pos, n, toFrame = line.to.frame)
        }
        val base = read(values, ctx, path, anchorGiven)
        if (base?.at?.exprs != null && (to != null || samples != null)) ctx.fail("$path.pos", "an '= expr' coefficient is a single point: it does not combine with to: / samples:")
        if (base == null) {
            if (to != null) ctx.fail("$path.to", "to: {v, w, s} needs frame: <id>")
            if (samples != null) ctx.fail("$path.samples", "samples: needs frame: <id> and to:")
            return null
        }
        if (to == null) {
            if (samples != null) ctx.fail("$path.samples", "samples: needs to: {v, w, s}")
            return base
        }
        val n = samples ?: ctx.fail("$path.samples", "to: needs samples: <n> (points from pos: to to:, ends included)")
        if (n < 2) ctx.fail("$path.samples", "samples: is at least 2 (pos: and to: are both drawn)")
        return FrameRef(base.frame, base.at, to, n)
    }

    /** The frame id and coefficients of a [cell] name, or null when [name] is not a frame cell. */
    public fun parse(name: String): FrameRef? {
        if (!name.startsWith(PREFIX)) return null
        val body = name.removePrefix(PREFIX)
        val exprAt = body.indexOf("#=")
        if (exprAt >= 0) {
            val parts = body.substring(exprAt + 2).split(';')
            val ex = parts.map { it.toDoubleOrNull() }
            return FrameRef(body.substring(0, exprAt), Coeff(ex[0] ?: 0.0, ex[1] ?: 0.0, ex[2] ?: 0.0, parts.mapIndexed { i, t -> if (ex[i] == null) t else null }))
        }
        if ('|' in body) {
            val a = body.substringBefore('|')
            val rest = body.substringAfter('|')
            val u = rest.substringAfter('@').toDouble()
            val b = rest.substringBefore('@')
            fun co(t: String): Coeff = t.substringAfter('#').split(',').let { Coeff(it[0].toDouble(), it[1].toDouble(), it[2].toDouble()) }
            return FrameRef(a.substringBefore('#'), co(a), co(b), 2, toFrame = b.substringBefore('#'), u = u)
        }
        val hash = body.indexOf('#')
        val caret = body.indexOf('^')
        val direction = caret >= 0 && (hash < 0 || caret < hash)
        val sep = if (direction) '^' else '#'
        val id = body.substringBefore(sep)
        val c = body.substringAfter(sep, "").split(',')
        if (c.size != 3) return FrameRef(id, Coeff.ZERO, direction = direction)
        return FrameRef(id, Coeff(c[0].toDouble(), c[1].toDouble(), c[2].toDouble()), direction = direction)
    }
}
