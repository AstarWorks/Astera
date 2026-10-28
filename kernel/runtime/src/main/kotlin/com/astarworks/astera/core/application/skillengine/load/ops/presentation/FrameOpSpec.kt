package com.astarworks.astera.core.application.skillengine.load.ops.presentation

import com.astarworks.astera.core.application.skillengine.compose.AnchorNames
import com.astarworks.astera.core.application.skillengine.compose.AnchorRegistry
import com.astarworks.astera.core.application.skillengine.compose.ExprClass
import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.compose.Occurrence
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldType
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVec
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVecs
import com.astarworks.astera.core.application.skillengine.load.grammar.Grammar
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.LocalAxes
import com.astarworks.astera.core.application.skillengine.load.grammar.Rotation
import com.astarworks.astera.core.application.skillengine.load.grammar.Rotations
import com.astarworks.astera.core.application.skillengine.load.grammar.Span
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.application.skillengine.load.motion.MotionLaws
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.expr.ExprEvalException
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.expr.FrameLookup
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.TrackKey
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Look
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Pose
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SpawnKind
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.table.WriteStats
import org.joml.Quaterniond
import org.joml.Vector3d

public enum class FrameMode { FOLLOW, SNAPSHOT }

/**
 * Decoded `frame` op (ADR-0303 1.1): an origin + a quaternion that things ride on; no appearance.
 *
 * ```yaml
 * - {id: blade, op: frame, times: 3, anchor: shears, part: blade_a, turn: [{axis: long, deg: "= i * 120"}], for: 40, motion: [...]}
 * ```
 */
public class FrameOp(
    override val id: OpId,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    /** Parent: a builtin (`caster`, `world`, `contact`, ...), a declared anchor, or a frame / family member (`blade[i]`). */
    public val anchor: String,
    /** `parts.<name>` of `content/frames/<item>.yaml`; its points and axes become this frame's. */
    public val part: String?,
    public val offset: FrameVec,
    public val turn: Rotation,
    public val mode: FrameMode,
    /** Rate laws already lowered to key tables by [MotionLaws]; null = a still frame. */
    public val motion: List<TrackKey>?,
) : OpNode

public object FrameOpSpec : OpSpec<FrameOp> {
    override val name: String = "frame"
    override val section: OpSection = OpSection.PRESENTATION
    override val timing: TimingKind? = TimingKind.EXTENDED

    private val anchorF = Field.required("anchor", Types.id, "parent: caster, victim/target, world, contact, a declared anchor, or another frame (blade[i])")
    private val partF = Field.optional("part", Types.nullable(Types.id), null, "weapon part of content/frames/<item>.yaml whose points and axes this frame takes")
    private val offsetF = Field.optional("offset", FrameVecs.type, FrameVec.Parts(Value.Literal(0.0), Value.Literal(0.0), Value.Literal(0.0)), "initial position in the parent's axes {forward, up, left}")
    private val turnF = Field.optional("turn", Rotations.type, Rotation.Turns(emptyList()), "initial orientation relative to the parent basis, [{axis, deg}, ...] (axes long/edge/face need part)")
    private val modeF = Field.optional("mode", Types.enumOf("follow", "snapshot"), "follow", "follow: re-read the parent at every send; snapshot: copy the pose once")
    private val motionF = Field.optional("motion", Types.nullable(MotionType), null, "laws on the origin and the axes (ADR-0303 3)")

    override val fields: Fields = Fields.of("frame", anchorF, partF, offsetF, turnF, modeF, motionF)

    private object MotionType : FieldType<List<TrackKey>> {
        override val label = "list of laws"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): List<TrackKey> = MotionLaws.read(raw, ctx, path)
        override fun schema(): Map<String, Any?> = MotionLaws.jsonSchema()
    }

    override fun decode(id: OpId, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<FrameOp, SkillDecodeError> =
        Grammar.guard {
            val times = timing?.times
            if (times != null && timing.every == null) {
                val n = ctx.resolveNumber(times)
                if (n != null && (n != Math.floor(n) || n < 1.0)) ctx.fail("${id.value}.times", "a frame family needs a whole times >= 1 (was $n)")
            }
            FrameOp(
                id, whenExpr, timing,
                values[anchorF], values[partF], values[offsetF], values[turnF],
                if (values[modeF] == "snapshot") FrameMode.SNAPSHOT else FrameMode.FOLLOW,
                values[motionF],
            )
        }

    override fun lower(op: FrameOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val ctx = GrammarContext(lc.skillId)
        return Grammar.guard { lowerOrAbort(op, lc, ctx) }
    }

    private fun lowerOrAbort(op: FrameOp, lc: LowerContext, ctx: GrammarContext): Lowered {
        val src = lc.src.value
        val t = op.timing
        // A family is `times` WITHOUT `every`; with `every` the unroller owns the repeat and each occurrence is one frame (2.0, rev4.1).
        val family: Int? = if (t?.times != null && t.every == null) staticTicks(lc, ctx, "$src.times", "times", t.times) else null
        val count = family ?: 1

        val item = if (op.part != null) {
            lc.skills.skill(lc.skillId)?.item
                ?: ctx.fail("$src.part", "part '${op.part}' needs the skill's item to find content/frames/<item>.yaml, but this skill has no item")
        } else null
        val modelPart = if (op.part != null) {
            lc.frames.item(item!!)?.partFor(op.part)
                ?: ctx.fail("$src.part", "content/frames/$item.yaml has no part '${op.part}'")
        } else null

        val life: Int? = lifeTicks(op, lc, ctx, src)
        val rows = ArrayList<Row>()
        val instances = ArrayList<InstanceId>()
        val writes = LinkedHashMap<InstanceId, WriteStats>()
        var basis = if (op.motion != null) ExprClass.T else ExprClass.S

        // The family base: instance prefix + the op id; members append `[k]`.
        val prefix = lc.instancePrefix.path
        val declared = InstanceId(prefix + InstanceId.Segment(op.id, null))

        for (k in 0 until count) {
            val occ = Occurrence(k, count, lc.occurrence)
            val ev = Evaluator(lc, ctx, occ)
            val instance = InstanceId(prefix + InstanceId.Segment(op.id, if (family != null) k else null))
            val scope = (lc.anchors as? AnchorRegistry)?.at(occ) ?: lc.anchors

            val parent = resolveParent(op.anchor, scope, src, ctx)
            val position = ev.vec(op.offset.toLocal(), "$src.offset")
            val axes = AxisResolver(modelPart?.axes ?: emptyMap())
            val rotation = ev.quat(op.turn.toValue(ctx, "$src.turn", axes), "$src.turn")
            val live = position is Val.Live || rotation is Val.Live
            if (live) basis = ExprClass.L

            val look = LinkedHashMap<String, Val<Any>>()
            look["mode"] = Val.Known(if (op.mode == FrameMode.SNAPSHOT) "snapshot" else "follow")
            if (item != null) look["item"] = Val.Known(item)
            if (op.part != null) look["part"] = Val.Known(op.part)
            rows += Row(
                0, instance,
                Action.Spawn(SpawnKind.FRAME, parent, op.anchor, Pose(position, rotation, Val.Known(Vector3d(1.0, 1.0, 1.0))), Look(look)),
                lc.src,
            )

            var segments = 0
            if (op.motion != null) {
                val keyRows = motionRows(op.motion, instance, position, rotation, lc, ctx, src)
                rows += keyRows
                segments = keyRows.size
            }
            if (life != null) rows += Row(life, instance, Action.End(instance), lc.src)

            instances += instance
            writes[instance] = WriteStats(segments, if (segments > 0) 1 else 0)
        }

        (lc.anchors as? AnchorRegistry)?.declare(declared, family, basis)
        return Lowered(rows = rows, instances = instances, writes = writes)
    }

    /** End tick of one frame: `lasts` when it repeats, else `for`; `for: cast` / `until` / no `for` leave the end to the owner and the unroller. */
    private fun lifeTicks(op: FrameOp, lc: LowerContext, ctx: GrammarContext, src: String): Int? {
        val t = op.timing ?: return null
        if (t.every != null) return t.lasts?.let { staticTicks(lc, ctx, "$src.lasts", "lasts", it) }
        return when (val span = t.span) {
            is Span.Ticks -> staticTicks(lc, ctx, "$src.for", "for", span.ticks)
            Span.UntilCastEnds, null -> null
        }
    }

    private fun staticTicks(lc: LowerContext, ctx: GrammarContext, path: String, field: String, v: Value<Double>): Int =
        when (val r = lc.requireStaticTiming(path, field, v)) {
            is Result.Success -> r.value
            is Result.Failure -> ctx.abort(r.error)
        }

    private val BUILTIN_ROOTS = setOf("world", "caster", "victim", "target", "contact")

    /**
     * An indexed name (`blade[i]`) must resolve to a spawned frame; a plain name is a builtin or an anchor declared in `anchors:` and stays
     * a name (the compiler checks declared anchors). A bare family name is ambiguous and is a load error.
     */
    private fun resolveParent(name: String, scope: com.astarworks.astera.core.application.skillengine.compose.AnchorScope, src: String, ctx: GrammarContext): InstanceId? {
        scope.resolve(name)?.let { return it.instance }
        val parts = AnchorNames.split(name) ?: ctx.fail("$src.anchor", "'$name' is not an anchor name")
        if (parts.index != null) {
            ctx.abort(SkillDecodeError.UndefinedReference(ctx.file, "$src.anchor", name))
        }
        if (parts.base !in BUILTIN_ROOTS && scope.resolve("${parts.base}[0]") != null) {
            ctx.fail("$src.anchor", "'${parts.base}' is a frame family; write ${parts.base}[i] (or an index) to pick the member")
        }
        return null
    }

    /** `long`, `edge`, `face = long x edge` from the part table, else the local frame axes. */
    private class AxisResolver(private val axes: Map<String, Vec3>) : com.astarworks.astera.core.application.skillengine.load.grammar.AxisResolver {
        override fun axis(name: String): Vec3? = when (name) {
            "long", "edge" -> axes[name]?.normalize()
            "face" -> {
                val l = axes["long"]
                val e = axes["edge"]
                if (l != null && e != null) l.cross(e).normalize() else null
            }
            else -> LocalAxes.axis(name)
        }
    }

    /**
     * Interim `motion` lowering: the key table of [MotionLaws] becomes `Key` rows (linear segments between keys; `step` is a cut). Eased
     * segments and live bases are LawSegmenter's job (W1-08) and fail loudly here instead of being flattened.
     */
    private fun motionRows(
        keys: List<TrackKey>, instance: InstanceId, basePosition: Val<Vector3d>, baseRotation: Val<Quaterniond>,
        lc: LowerContext, ctx: GrammarContext, src: String,
    ): List<Row> {
        val bp = (basePosition as? Val.Known)?.value ?: ctx.fail("$src.motion", "motion on a live offset needs the law segmenter (W1-08)")
        val br = (baseRotation as? Val.Known)?.value ?: ctx.fail("$src.motion", "motion on a live turn needs the law segmenter (W1-08)")
        val out = ArrayList<Row>()
        var prev: TrackKey? = null
        var pos = Vector3d(bp)
        var rot = Quaterniond(br)
        var scale = Vector3d(1.0, 1.0, 1.0)
        for ((n, key) in keys.sortedBy { it.tick }.withIndex()) {
            val ease = key.ease?.trim()?.lowercase()
            if (ease != null && ease != "linear" && ease != "step") {
                ctx.fail("$src.motion[$n]", "eased segment '$ease' needs the law segmenter (W1-08)")
            }
            (key.offset as? Value.Literal)?.let { pos = Vector3d(bp).add(it.value.x, it.value.y, it.value.z) }
            if (key.offset != null && key.offset !is Value.Literal) ctx.fail("$src.motion[$n]", "a key offset must be a literal here")
            (key.turn as? Value.Literal)?.let { rot = Quaterniond(br).mul(turnQuat(it.value)) }
            if (key.turn != null && key.turn !is Value.Literal) ctx.fail("$src.motion[$n]", "a key turn must be a literal here")
            (key.scale as? Value.Literal)?.let { scale = Vector3d(it.value.x, it.value.y, it.value.z) }
            if (key.scale != null && key.scale !is Value.Literal) ctx.fail("$src.motion[$n]", "a key scale must be a literal here")
            val p = prev
            val cut = ease == "step" || p == null
            val from = if (cut) key.tick else p!!.tick
            val duration = if (cut) 0 else key.tick - p!!.tick
            out += Row(
                from, instance,
                Action.Key(instance, duration, Val.Known(Vector3d(pos)), Val.Known(Quaterniond(rot)), Val.Known(Vector3d(scale))),
                lc.src,
            )
            prev = key
        }
        return out
    }

    /** TrackKey `turn` = rotation vector (axis * degrees). */
    private fun turnQuat(v: Vec3): Quaterniond {
        val deg = v.length()
        if (deg < 1e-12) return Quaterniond()
        val a = Math.toRadians(deg)
        return Quaterniond().fromAxisAngleRad(v.x / deg, v.y / deg, v.z / deg, a)
    }

    /** Static evaluation of the pose values of ONE instance: literals and expressions of `i`, `n`, `parent.i` fold to Known; the rest stays Live. */
    private class Evaluator(private val lc: LowerContext, private val ctx: GrammarContext, private val occ: Occurrence) {
        private val variables: Map<String, ExprValue>
        private val spec: ExprEnvSpec

        init {
            val vars = LinkedHashMap<String, ExprValue>()
            var o: Occurrence? = occ
            var p = ""
            while (o != null) {
                vars[p + "i"] = ExprValue.Num(o.i.toDouble())
                vars[p + "n"] = ExprValue.Num(o.n.toDouble())
                o = o.parent
                p += "parent."
            }
            variables = vars
            spec = ExprEnvSpec(variables = vars.mapValues { ExprType.NUM })
        }

        private val noFrames = object : FrameLookup {
            override fun worldPose(frame: String, point: String?) = null
            override fun velocity(frame: String, point: String?) = Vec3.ZERO
        }

        private fun fold(source: String, expected: ExprType, path: String): ExprValue? {
            val compiled = when (val r = lc.exprs.compile(source, spec, expected)) {
                is Result.Success -> r.value
                is Result.Failure -> ctx.abort(SkillDecodeError.BadExpr(ctx.file, path, r.error))
            }
            return try {
                compiled.eval(ExprEnv(variables, noFrames))
            } catch (_: ExprEvalException) {
                null // depends on a run-time value: stays Live
            }
        }

        fun vec(v: Value<Vec3>, path: String): Val<Vector3d> = when (v) {
            is Value.Literal -> Val.Known(Vector3d(v.value.x, v.value.y, v.value.z))
            is Value.ParamRef -> Val.Live(v.name, null)
            is Value.Expr -> (fold(v.source, ExprType.VEC3, path) as? ExprValue.Vec3)
                ?.let { Val.Known(Vector3d(it.value.x, it.value.y, it.value.z)) } ?: Val.Live(v.source, null)
        }

        fun quat(v: Value<Quaternion>, path: String): Val<Quaterniond> = when (v) {
            is Value.Literal -> Val.Known(v.value.toJoml())
            is Value.ParamRef -> Val.Live(v.name, null)
            is Value.Expr -> (fold(v.source, ExprType.QUAT, path) as? ExprValue.Quat)
                ?.let { Val.Known(it.value.toJoml()) } ?: Val.Live(v.source, null)
        }

        private fun Quaternion.toJoml(): Quaterniond = Quaterniond(x, y, z, w)
    }
}
