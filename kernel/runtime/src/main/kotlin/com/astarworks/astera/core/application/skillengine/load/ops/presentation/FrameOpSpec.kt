package com.astarworks.astera.core.application.skillengine.load.ops.presentation

import com.astarworks.astera.core.application.skillengine.compose.AnchorNames
import com.astarworks.astera.core.application.skillengine.compose.AnchorRegistry
import com.astarworks.astera.core.application.skillengine.compose.ExprClass
import com.astarworks.astera.core.application.skillengine.compose.LawSegmenter
import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.compose.Occurrence
import com.astarworks.astera.core.application.skillengine.compose.AnchorChain
import com.astarworks.astera.core.application.skillengine.compose.PoseCompose
import com.astarworks.astera.core.application.skillengine.load.DecodeAbort
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldType
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVec
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVecs
import com.astarworks.astera.core.application.skillengine.load.grammar.Grammar
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.ItemPart
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
import com.astarworks.astera.core.application.skillengine.load.motion.LawSource
import com.astarworks.astera.core.application.skillengine.load.motion.MotionLaw
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Look
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.TickRange
import com.astarworks.astera.core.domain.skillengine.table.Pose
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SpawnKind
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.table.WriteStats
import com.astarworks.astera.core.domain.skillengine.math.Quaterniond
import com.astarworks.astera.core.domain.skillengine.math.Vector3d

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
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    /** Parent: a builtin (`caster`, `world`, `contact`, ...), a declared anchor, or a frame / family member (`blade[i]`). */
    public val anchor: String,
    /** `parts.<name>` of `content/frames/<item>.yaml`; its points and axes become this frame's. */
    public val part: String?,
    public val offset: FrameVec,
    public val turn: Rotation,
    public val mode: FrameMode,
    /** R23: whose axes the frame takes (`caster` / `victim` / `world`); null = the anchor's own axes. Re-read per send (follow) or once (snapshot). */
    public val axes: String?,
    /** Motion laws decoded by [MotionLaws]; segmented into `Key` rows by [LawSegmenter] at compile; null = a still frame. */
    public val motion: LawSource?,
) : OpNode

public object FrameOpSpec : OpSpec<FrameOp> {
    override val name: String = "frame"
    override val section: OpSection = OpSection.PRESENTATION
    override val timing: TimingKind? = TimingKind.EXTENDED

    private val anchorF = Field.required("anchor", Types.id, "parent: caster, victim/target, world, contact, a declared anchor, or another frame (blade[i])")
    private val partF = ItemPart.field
    private val offsetF = Field.optional("offset", FrameVecs.type, FrameVec.Parts(Value.Literal(0.0), Value.Literal(0.0), Value.Literal(0.0)), "initial position in the parent's axes {forward, up, left}")
    private val turnF = Field.optional("turn", Rotations.type, Rotation.Turns(emptyList()), "initial orientation relative to the parent basis, [{axis, deg}, ...] (axes long/edge/face need part)")
    private val modeF = Field.optional("mode", Types.enumOf("follow", "snapshot"), "follow", "follow: re-read the parent at every send; snapshot: copy the pose once")
    private val axesF = Field.optional("axes", Types.nullable(Types.enumOf("caster", "victim", "world")), null, "whose axes the frame takes: caster | victim | world; absent = the anchor's own axes")
    private val motionF = Field.optional<RawMotion?>("motion", Types.nullable(MotionType), null, "laws on the origin and the axes (ADR-0303 3)")

    override val fields: Fields = Fields.of("frame", anchorF, partF, offsetF, turnF, modeF, axesF, motionF)

    /** The raw `motion:` list: the laws are read in [decode], once the sibling `part:` (so `long` / `edge` / `face`) and the param defaults are known. */
    private class RawMotion(val raw: Any?, val path: String)

    private object MotionType : FieldType<RawMotion> {
        override val label = "list of laws"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): RawMotion = RawMotion(raw, path)
        override fun schema(): Map<String, Any?> = MotionLaws.jsonSchema()
    }

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<FrameOp, SkillDecodeError> =
        Grammar.guard {
            val times = timing?.times
            if (times != null && timing.every == null) {
                val n = ctx.resolveNumber(times)
                if (n != null && (n != Math.floor(n) || n < 1.0)) ctx.fail("${id.value}.times", "a frame family needs a whole times >= 1 (was $n)")
            }
            FrameOp(
                id, src, whenExpr, timing,
                values[anchorF], values[partF], values[offsetF], values[turnF],
                if (values[modeF] == "snapshot") FrameMode.SNAPSHOT else FrameMode.FOLLOW,
                values[axesF], values[motionF]?.let { m -> LawSource(m.raw, ctx.forLaws(AxisResolver(lawAxes(values[partF], ctx))), m.path).also { it.probe(probeCopies(times, ctx)) } },
            )
        }

    /** The `n` a first-copy probe binds: the family size when `times` folds at load, else 1 (a `times:` of CEL is checked at lowering). */
    private fun probeCopies(times: Value<Double>?, ctx: GrammarContext): Int =
        times?.let { ctx.resolveNumber(it) }?.toInt()?.coerceAtLeast(1) ?: 1

    /**
     * `long` / `edge` of the frame's `part`. The compiler's decode has the item frame table and gets the part's real axes (absent part = no axes);
     * the document decoder has no table, so it only validates the law grammar against stand-in axes (the compile-time decode re-reads the laws).
     */
    private fun lawAxes(part: String?, ctx: GrammarContext): Map<String, Vec3> {
        if (part == null) return emptyMap()
        val table = ctx.partAxes ?: return mapOf("long" to Vec3(0.0, 1.0, 0.0), "edge" to Vec3(1.0, 0.0, 0.0))
        return table(part) ?: emptyMap()
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

        val resolved = op.part?.let { part ->
            when (val r = ItemPart.resolve(lc, part)) {
                is Result.Success -> r.value
                is Result.Failure -> ctx.fail("$src.part", r.error)
            }
        }
        val item = resolved?.item
        val modelPart = resolved?.part

        val life: Int? = lifeTicks(op, lc, ctx, src)
        val rows = ArrayList<Row>()
        val writes = LinkedHashMap<InstanceId, WriteStats>()
        val subTables = ArrayList<SubTable>()
        var basis = if (op.motion != null) ExprClass.T else ExprClass.S
        var chainOut: AnchorChain? = null

        // The unroller owns every repetition and id (item 5): this call lowers exactly one instance, `lc.self`.
        val instance = lc.self
        val declared = InstanceId(instance.path.dropLast(1) + InstanceId.Segment(op.id, null))
        val occ = lc.occurrence
        run {
            val ev = Evaluator(lc, ctx, occ)
            val scope = lc.anchors

            val parent = resolveParent(op.anchor, scope, src, ctx, op.id)
            val position = ev.vec(op.offset.toLocal(), "$src.offset")
            val axes = AxisResolver(modelPart?.axes ?: emptyMap())
            val rotation = ev.quat(op.turn.toValue(ctx, "$src.turn", axes), "$src.turn")
            val live = position is Val.Live || rotation is Val.Live
            if (live) basis = ExprClass.L

            val look = LinkedHashMap<String, Val<Any>>()
            look["mode"] = Val.Known(if (op.mode == FrameMode.SNAPSHOT) "snapshot" else "follow")
            op.axes?.let { look["axes"] = Val.Known(it) }
            if (item != null) look["item"] = Val.Known(item)
            if (op.part != null) look["part"] = Val.Known(op.part)
            // the part's model points ride in the table, so an expression `<id>.tip` reads a number the dump shows (3.5), not a lookup at play time
            modelPart?.points?.forEach { (name, p) -> look["point.$name"] = Val.Known(Vector3d(p.x, p.y, p.z)) }
            // A frame riding a MOVING frame skips it (a rider's keys are absolute, so riding the rec would count the frame's laws twice): it
            // spawns on the chain's parent with the chain's base folded in and composes the chain's laws into its own keys.
            val inChain = lc.anchors.chain(op.anchor)
            val ownPose = Pose(position, rotation, Val.Known(Vector3d(1.0, 1.0, 1.0)))
            val spawnPose = if (inChain != null) PoseCompose.combine(inChain.base, ownPose) { ctx.fail("$src.offset", it) } else ownPose
            val spawnParent = if (inChain != null) inChain.parent else parent
            val spawnHost = inChain?.parentName ?: AnchorNames.host(lc.anchors, op.anchor)
            rows += Row(0, instance, Action.Spawn(SpawnKind.FRAME, spawnParent, spawnHost, spawnPose, Look(look)), lc.src)

            var segments = 0
            // The anchor's own laws ride first (left_rotation = anchor.rot(t) * rider), then this frame's own.
            val own = op.motion?.laws(occ).orEmpty()
            val laws = (inChain?.laws ?: lc.anchors.laws(op.anchor)) + own
            if (laws.isNotEmpty()) {
                val moved = when (val r = LawSegmenter.segments(instance, laws, lc)) {
                    is Result.Failure -> throw DecodeAbort(r.error)
                    is Result.Success -> r.value
                }
                // a law outlasting the frame's `for:` writes nothing once the frame has ended: rows at or after the End tick are dead
                val inLife = if (life != null) moved.rows.filter { it.tick < life } else moved.rows
                val withOwn = PoseCompose.composeBase(inLife, position, rotation, Val.Known(Vector3d(1.0, 1.0, 1.0))) { ctx.fail("$src.motion", it) }
                rows += if (inChain != null) PoseCompose.composeBase(withOwn, inChain.base.position, inChain.base.rotation, inChain.base.scale) { ctx.fail("$src.motion", it) } else withOwn
                subTables += moved.subTables
                segments = inLife.count { it.action is Action.Key }
                if (own.isNotEmpty() || inChain != null) {
                    chainOut = AnchorChain(spawnPose, spawnParent, spawnHost, laws)
                }
            }
            if (life != null) rows += Row(life, instance, Action.End(instance), lc.src)
            op.timing?.until?.let { ev ->
                // `until` is a mask: the event's sub-table ends this frame the first time it fires (same law as displays and held models).
                val hi = lc.clockOffset + (life ?: lc.config.maxLifeTicks)
                subTables += SubTable(
                    EventPath(ev), lc.state, TickRange(lc.clockOffset, maxOf(lc.clockOffset, hi)), 1,
                    listOf(Row(0, instance, Action.End(instance), lc.src)), emptyList(),
                )
            }
            writes[instance] = WriteStats(segments, if (segments > 0) 1 else 0)
        }

        // a frame with laws declares its pose chain: a rider skips the frame's rec and composes the chain into its own absolute keys, so the laws count once
        // (a projectile body has no rows and exports its `laws` instead)
        (lc.anchors as? AnchorRegistry)?.declare(declared, family, basis, emptyList(), if (family != null) occ.i else null, chainOut)
        return Lowered(rows = rows, subTables = subTables, instances = listOf(instance), writes = writes)
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

    /**
     * An indexed name (`blade[i]`) must resolve to a spawned frame; a plain name is a builtin or an anchor declared in `anchors:` and stays
     * a name (the compiler checks declared anchors). A bare family name is ambiguous and is a load error.
     */
    private fun resolveParent(name: String, scope: com.astarworks.astera.core.application.skillengine.compose.AnchorScope, src: String, ctx: GrammarContext, id: OpId): InstanceId? {
        scope.resolve(name)?.let { return it.instance }
        val parts = AnchorNames.split(name) ?: ctx.fail("$src.anchor", "'$name' is not an anchor name")
        if (parts.index != null) {
            ctx.abort(SkillDecodeError.UndefinedReference(ctx.file, "$src.anchor", name))
        }
        AnchorNames.bareFamilyError(scope, name, "frame '${id.value.substringAfterLast('/')}'")?.let { ctx.fail("$src.anchor", it) }
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
