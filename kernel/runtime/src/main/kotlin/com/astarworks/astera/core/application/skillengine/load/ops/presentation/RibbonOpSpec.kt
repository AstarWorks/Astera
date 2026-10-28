package com.astarworks.astera.core.application.skillengine.load.ops.presentation

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldType
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.Grammar
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.Span
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Look
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Pose
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SpawnKind
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.math.Quaterniond
import com.astarworks.astera.core.domain.skillengine.math.Vector3d

/** Trail width: a fraction of the root-to-tip length, or an absolute size in blocks. */
public sealed interface RibbonWidth {
    public data class Frac(val v: Value<Double>) : RibbonWidth
    public data class Blocks(val v: Value<Double>) : RibbonWidth
}

/** Which of the two ribbon shapes the author wrote (ADR-0303 section 8: `trail` and `line` both become `ribbon`). */
public sealed interface RibbonShape {
    /** History trail of the tip anchor [of]; [root] fans the ribbon from a second anchor (blade edge). [history] = ticks of tip history drawn. */
    public data class Trail(val of: String, val root: String?, val history: Value<Double>, val width: RibbonWidth, val style: String) : RibbonShape

    /** Straight bar stretched between two anchors. */
    public data class Line(val from: String, val to: String, val thickness: Value<Double>) : RibbonShape
}

/** Decoded `ribbon` op. */
public class RibbonOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val shape: RibbonShape,
    /** 0xAARRGGBB. */
    public val argb: Int,
    /** Fade exponent over the history (1.0 = linear). */
    public val fade: Value<Double>,
    public val stepDeg: Value<Double>,
    public val lagTicks: Value<Double>,
    public val doubleSided: Value<Boolean>,
    /** Raw `motion:` list; lowered by LawSegmenter (W1-08). Null when absent. */
    public val motion: List<Any?>?,
) : OpNode

public object RibbonOpSpec : OpSpec<RibbonOp> {
    override val name: String = "ribbon"
    override val section: OpSection = OpSection.PRESENTATION
    override val timing: TimingKind? = TimingKind.EXTENDED

    private val rawList: FieldType<List<Any?>> = object : FieldType<List<Any?>> {
        override val label = "list of motion laws"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): List<Any?> =
            raw as? List<*> ?: ctx.mismatch(path, label, raw)
        override fun schema(): Map<String, Any?> = linkedMapOf("type" to "array")
    }

    private val of = Field.optional("of", Types.nullable(Types.id), null, "trail: tip anchor whose history is drawn")
    private val root = Field.optional("root", Types.nullable(Types.id), null, "trail: second anchor the ribbon fans out from")
    private val from = Field.optional("from", Types.nullable(Types.id), null, "line: start anchor")
    private val to = Field.optional("to", Types.nullable(Types.id), null, "line: end anchor")
    private val thickness = Field.optional("thickness", Types.nullable(Types.number), null, "line: bar thickness in blocks")
    private val color = Field.required("color", Types.id, "#RRGGBB or #AARRGGBB")
    private val style = Field.optional("style", Types.nullable(Types.enumOf("soft", "crisp", "both")), null, "trail: edge style (required for a trail)")
    private val history = Field.optional("history", Types.nullable(Types.number), null, "trail: ticks of tip history drawn (required for a trail)")
    private val widthFrac = Field.optional("width_frac", Types.nullable(Types.number), null, "trail: width as a fraction of the root-to-tip length (width_frac or width)")
    private val widthBlocks = Field.optional("width", Types.nullable(Types.number), null, "trail: width in blocks, no root (width_frac or width)")
    private val fade = Field.optional("fade", Types.number, Value.Literal(1.0), "alpha falloff exponent along the history; 1.0 = linear")
    private val stepDeg = Field.optional("step_deg", Types.number, Value.Literal(30.0), "max angle between two samples before the trail is subdivided (5..90)")
    private val lagTicks = Field.optional("lag_ticks", Types.number, Value.Literal(0.0), "ticks the drawn tip trails behind the anchor")
    private val doubleSided = Field.optional("double_sided", Types.bool, Value.Literal(false), "render both faces")
    private val motion = Field.optional("motion", Types.nullable(rawList), null, "motion laws (stretch / grow ...), lowered by the law segmenter")

    override val fields: Fields = Fields.of(
        "ribbon", of, root, from, to, thickness, color, style, history, widthFrac, widthBlocks, fade, stepDeg, lagTicks, doubleSided, motion,
    )

    private val ARGB = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<RibbonOp, SkillDecodeError> =
        Grammar.guard {
            val p = "ribbon[${id.value}]"
            if (timing == null) ctx.fail(p, "a ribbon needs a window: for (ticks) or until (ADR-0303 section 5)")
            if (timing.every != null) ctx.fail("$p.every", "a ribbon is one instance; repeat it with a group, or use times for a family")
            if (timing.span == null && timing.until == null) ctx.fail(p, "a ribbon needs an end: for or until (ADR-0303 section 5)")
            val tip = values[of]
            val line = values[from] != null || values[to] != null || values[thickness] != null
            val trail = tip != null || values[root] != null || values[style] != null || values[widthFrac] != null || values[widthBlocks] != null || values[history] != null
            if (line && trail) ctx.fail(p, "a ribbon is either a trail (of/root/style/width_frac) or a line (from/to/thickness), not both")
            val shape: RibbonShape = if (line) {
                val f = values[from] ?: ctx.fail("$p.from", "a line ribbon needs from")
                val t = values[to] ?: ctx.fail("$p.to", "a line ribbon needs to")
                val th = values[thickness] ?: ctx.fail("$p.thickness", "a line ribbon needs thickness")
                if (timing.lasts != null) ctx.fail("$p.lasts", "lasts is the history length of a trail; a line has none")
                RibbonShape.Line(f, t, th)
            } else {
                val o = tip ?: ctx.fail("$p.of", "a trail ribbon needs of (or write from/to for a line)")
                val st = values[style] ?: ctx.fail("$p.style", "a trail ribbon needs style: soft, crisp or both (ADR-0303 section 2.0b)")
                val w = when {
                    values[widthFrac] != null && values[widthBlocks] != null -> ctx.fail("$p.width", "write width_frac or width, not both")
                    values[widthFrac] != null -> {
                        if (values[root] == null) ctx.fail("$p.width_frac", "width_frac is a fraction of root-to-tip and needs root; without root write width (blocks)")
                        RibbonWidth.Frac(values[widthFrac]!!)
                    }
                    values[widthBlocks] != null -> {
                        if (values[root] != null) ctx.fail("$p.width", "width (blocks) forbids root; with root write width_frac")
                        RibbonWidth.Blocks(values[widthBlocks]!!)
                    }
                    else -> ctx.fail("$p.width_frac", "a trail ribbon needs width_frac (with root) or width (without root) (ADR-0303 section 2.0b)")
                }
                val h = values[history] ?: ctx.fail("$p.history", "a trail ribbon needs history: the number of ticks of tip history drawn")
                RibbonShape.Trail(o, values[root], h, w, st)
            }
            val hex = values[color]
            val m = ARGB.matchEntire(hex) ?: ctx.fail("$p.color", "'$hex' is not #RRGGBB or #AARRGGBB")
            val digits = m.groupValues[1]
            val argb = if (digits.length == 6) (0xFF shl 24) or digits.toInt(16) else digits.toLong(16).toInt()
            val deg = ctx.resolveNumber(values[stepDeg])
            if (deg != null && deg !in 5.0..90.0) ctx.fail("$p.step_deg", "step_deg $deg is outside 5..90")
            RibbonOp(id, src, whenExpr, timing, shape, argb, values[fade], values[stepDeg], values[lagTicks], values[doubleSided], values[motion])
        }

    private fun known(v: Value<Double>, path: String, lc: LowerContext): Result<Val<Double>, SkillDecodeError> =
        when (v) {
            is Value.Literal -> Result.Success(Val.Known(v.value))
            else -> Result.Failure(SkillDecodeError.Invalid(lc.skillId, path, "ribbon field must be a literal until W1-24 lands live values (got ${v::class.simpleName})"))
        }

    private fun knownBool(v: Value<Boolean>, path: String, lc: LowerContext): Result<Val<Boolean>, SkillDecodeError> =
        when (v) {
            is Value.Literal -> Result.Success(Val.Known(v.value))
            else -> Result.Failure(SkillDecodeError.Invalid(lc.skillId, path, "ribbon field must be a literal until W1-24 lands live values"))
        }

    /** `blade[i].tip` -> anchor `blade[k]`, point `tip`. [i] is replaced by the family index. */
    private fun anchorOf(text: String, k: Int, lc: LowerContext, path: String): Result<Pair<InstanceId, String>, SkillDecodeError> {
        val sub = text.replace("[i]", "[$k]")
        val name = sub.substringBefore('.')
        val resolved = lc.anchors.resolve(name)
            ?: return Result.Failure(SkillDecodeError.UndefinedReference(lc.skillId, path, name))
        return Result.Success(resolved.instance to sub)
    }

    override fun lower(op: RibbonOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val p = lc.src.value
        val timing = op.timing ?: return Result.Failure(SkillDecodeError.Invalid(lc.skillId, p, "ribbon has no timing"))
        if (op.motion != null) {
            // TODO(W1-08): replace by LawSegmenter.segments(instance, laws, lc) once MotionLaws.type is wired into this op.
            return Result.Failure(SkillDecodeError.Invalid(lc.skillId, "$p.motion", "ribbon motion laws need the law segmenter (W1-08), which is not merged"))
        }
        val span = timing.span
        val life = when (span) {
            is Span.Ticks -> when (val r = lc.requireStaticTiming("$p.for", "for", span.ticks)) {
                is Result.Success -> r.value
                is Result.Failure -> return r
            }
            Span.UntilCastEnds -> return Result.Failure(SkillDecodeError.Invalid(lc.skillId, "$p.for", "ribbon: 'for: cast' is not supported; give a tick count"))
            null -> return Result.Failure(SkillDecodeError.Invalid(lc.skillId, p, "ribbon needs for (the trail points are enumerated over the window)"))
        }
        // The unroller owns every repetition and id (item 5): this call lowers exactly one instance, `lc.self`.
        val self = lc.self
        val k = lc.occurrence.i
        val rows = ArrayList<Row>()
        run {
            val look = LinkedHashMap<String, Val<Any>>()
            look["argb"] = Val.Known(op.argb)
            val tipAnchor: InstanceId?
            val tipName: String
            when (val s = op.shape) {
                is RibbonShape.Trail -> {
                    val tip = when (val r = anchorOf(s.of, k, lc, "$p.of")) { is Result.Success -> r.value; is Result.Failure -> return r }
                    tipAnchor = tip.first; tipName = tip.second
                    s.root?.let { rt ->
                        val r2 = when (val r = anchorOf(rt, k, lc, "$p.root")) { is Result.Success -> r.value; is Result.Failure -> return r }
                        look["root"] = Val.Known(r2.second)
                        look["root_anchor"] = Val.Known(r2.first.text)
                    }
                    val hist = when (val r = known(s.history, "$p.history", lc)) { is Result.Success -> r.value; is Result.Failure -> return r }
                    look["shape"] = Val.Known("trail")
                    look["history_ticks"] = hist
                    when (val wd = s.width) {
                        is RibbonWidth.Frac -> look["width_frac"] = when (val r = known(wd.v, "$p.width_frac", lc)) { is Result.Success -> r.value; is Result.Failure -> return r }
                        is RibbonWidth.Blocks -> look["width"] = when (val r = known(wd.v, "$p.width", lc)) { is Result.Success -> r.value; is Result.Failure -> return r }
                    }
                    look["style"] = Val.Known(s.style)
                }
                is RibbonShape.Line -> {
                    val a = when (val r = anchorOf(s.from, k, lc, "$p.from")) { is Result.Success -> r.value; is Result.Failure -> return r }
                    val b = when (val r = anchorOf(s.to, k, lc, "$p.to")) { is Result.Success -> r.value; is Result.Failure -> return r }
                    tipAnchor = a.first; tipName = a.second
                    look["shape"] = Val.Known("line")
                    look["to"] = Val.Known(b.second)
                    look["to_anchor"] = Val.Known(b.first.text)
                    look["thickness"] = when (val r = known(s.thickness, "$p.thickness", lc)) { is Result.Success -> r.value; is Result.Failure -> return r }
                }
            }
            look["fade"] = when (val r = known(op.fade, "$p.fade", lc)) { is Result.Success -> r.value; is Result.Failure -> return r }
            look["step_deg"] = when (val r = known(op.stepDeg, "$p.step_deg", lc)) { is Result.Success -> r.value; is Result.Failure -> return r }
            look["lag_ticks"] = when (val r = known(op.lagTicks, "$p.lag_ticks", lc)) { is Result.Success -> r.value; is Result.Failure -> return r }
            look["double_sided"] = when (val r = knownBool(op.doubleSided, "$p.double_sided", lc)) { is Result.Success -> r.value; is Result.Failure -> return r }
            timing.until?.let { look["until"] = Val.Known(it) }

            val pose = Pose(Val.Known(Vector3d()), Val.Known(Quaterniond()), Val.Known(Vector3d(1.0, 1.0, 1.0)))
            rows += Row(0, self, Action.Spawn(SpawnKind.RIBBON, tipAnchor, tipName, pose, Look(look)), lc.src)
            if (op.shape is RibbonShape.Trail) {
                for (t in 0 until life) {
                    rows += Row(t, self, Action.TrailPoint(self, Val.Live("anchor:$tipName", null)), lc.src)
                }
            }
            rows += Row(life, self, Action.End(self), lc.src)
        }
        return Result.Success(Lowered(rows, instances = listOf(self)))
    }
}
