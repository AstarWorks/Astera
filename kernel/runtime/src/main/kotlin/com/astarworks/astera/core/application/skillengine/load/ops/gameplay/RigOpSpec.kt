package com.astarworks.astera.core.application.skillengine.load.ops.gameplay

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVec
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVecs
import com.astarworks.astera.core.application.skillengine.load.grammar.Grammar
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.Rotation
import com.astarworks.astera.core.application.skillengine.load.grammar.Rotations
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.application.skillengine.load.rig.RigBlade
import com.astarworks.astera.core.application.skillengine.load.rig.RigSwings
import com.astarworks.astera.core.application.skillengine.load.rig.SwingStep
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.Val
import org.joml.Vector3d
import kotlin.math.acos
import kotlin.math.sqrt

/** One `freezes:` entry: a hit-stop window of [ticks] frozen ticks at rig-relative tick [at]. */
public data class RigFreezeSpec(val at: Int, val ticks: Int)

/**
 * Decoded `rig` op (the skin rig, ADR-0303 SKL-17): poses the held item's model through a wrist rotation that follows
 * [swing] (tip direction from / via / to, edge leading or trailing). The span is the rig's lifetime.
 */
public class RigOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val parent: String,
    public val anim: String?,
    public val hand: Boolean,
    public val pivot: FrameVec,
    public val tilt: Vector3d,
    public val rest: Vector3d,
    public val swing: List<SwingStep>,
        public val bladePart: String?,
    public val sampleTicks: Int,
    public val duration: Value<Double>,
    public val respectHitStop: Boolean,
    public val bodyRespectHitStop: Boolean,
    public val freezes: List<RigFreezeSpec>,
    public val tip: FrameVec?,
    public val root: FrameVec?,
    public val edge: FrameVec?,
) : OpNode

public object RigOpSpec : OpSpec<RigOp> {
    private val parent = Field.required("parent", Types.id, "frame the rig hangs on")
    private val anim = Field.optional("anim", Types.nullable(Types.id), null, "rig animation name")
    private val hand = Field.optional("hand", Types.bool, Value.Literal(true), "true = held in the hand")
    private val pivot = Field.optional("pivot", FrameVecs.type, FrameVec.Parts(Value.Literal(0.0), Value.Literal(0.0), Value.Literal(0.0)), "pivot offset in the parent frame")
    private val tilt = Field.optional("tilt", Rotations.type, Rotation.Turns(emptyList()), "fixed tilt of the item in the hand (literal turns)")
    private val rest = Field.optional("rest", Rotations.type, Rotation.Turns(emptyList()), "rest orientation of the model (literal turns)")
    private val swing = Field.optional("swing", RigSwings.type, emptyList(), "edge-travel steps: from / via / to, edge leading|trailing")

    private val bladePart = Field.optional("part", Types.nullable(Types.id), null, "part of the skill item's content/frames/<item>.yaml; absent = held")
    private val sampleTicks = Field.optional("sample_ticks", Types.number, Value.Literal(2.0), "ticks between key samples, >= 1")
    private val respectHitStop = Field.optional("respect_hit_stop", Types.bool, Value.Literal(false), "the rig freezes during hit-stop")
    private val bodyRespectHitStop = Field.optional("body_respect_hit_stop", Types.bool, Value.Literal(false), "the body freezes during hit-stop")
    private val freezeAt = Field.required("at", Types.number, "rig-relative tick")
    private val freezeTicks = Field.required("ticks", Types.number, "frozen ticks")
    private val freezes = Field.optional(
        "freezes", Types.list(Types.nested(Fields.of("rig-freeze", freezeAt, freezeTicks))), emptyList(), "hit-stop windows",
    )
    private val tip = Field.optional("tip", Types.nullable(FrameVecs.type), null, "tip point in the rig frame")
    private val root = Field.optional("root", Types.nullable(FrameVecs.type), null, "root point in the rig frame")
    private val edge = Field.optional("edge", Types.nullable(FrameVecs.type), null, "edge point in the rig frame")

    override val name: String = "rig"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.EXTENDED
    override val fields: Fields = Fields.of(
        "rig", parent, anim, hand, pivot, tilt, rest, swing, bladePart, sampleTicks,
        respectHitStop, bodyRespectHitStop, freezes, tip, root, edge,
    )

    /** Rotation turns that are all literal, as a rotation vector (axis * degrees). */
    private fun rotationVector(r: Rotation, ctx: GrammarContext, path: String): Vector3d {
        val q = when (val v = if (r is Rotation.Turns && r.turns.isEmpty()) Value.Literal(Quaternion(0.0, 0.0, 0.0, 1.0)) else rotationValue(r, ctx, path)) {
            is Value.Literal -> v.value.normalized()
            else -> ctx.fail(path, "must be literal turns (a rig tilt / rest is fixed at load)")
        }
        val w = q.w.coerceIn(-1.0, 1.0)
        val s = sqrt(1.0 - w * w)
        if (s < 1e-9) return Vector3d(0.0, 0.0, 0.0)
        val deg = Math.toDegrees(2.0 * acos(w))
        return Vector3d(q.x / s * deg, q.y / s * deg, q.z / s * deg)
    }

    private fun rotationValue(r: Rotation, ctx: GrammarContext, path: String): Value<Quaternion> = when (r) {
        is Rotation.Turns -> r.toValue(ctx, path)
        is Rotation.Whole -> r.value
    }

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<RigOp, SkillDecodeError> =
        Grammar.guard {
            val path = "rig(${id.value})"
            val probe = DashOp(id, src, whenExpr, timing, Value.Literal(0.0), Value.Literal(0.0), values[pivot], null, false, true, true, false, null, Value.Literal(1.2))
            val duration = CasterMotion.ticksOf(probe, ctx, path)
            fun flag(f: Field<Value<Boolean>>): Boolean = (values[f] as? Value.Literal)?.value ?: ctx.fail("$path.${f.name}", "must be a literal true/false")
            val sample = ctx.resolveNumber(values[sampleTicks])?.toInt() ?: ctx.fail("$path.sample_ticks", "must be a number known at load")
            if (sample < 1) ctx.fail("$path.sample_ticks", "must be >= 1")
            val steps = values[swing]
            val frz = values[freezes].mapIndexed { i, f ->
                val a = ctx.resolveNumber(f[freezeAt])?.toInt() ?: ctx.fail("$path.freezes[$i].at", "must be a number known at load")
                val t = ctx.resolveNumber(f[freezeTicks])?.toInt() ?: ctx.fail("$path.freezes[$i].ticks", "must be a number known at load")
                if (a < 0 || t < 1) ctx.fail("$path.freezes[$i]", "needs at >= 0 and ticks >= 1")
                RigFreezeSpec(a, t)
            }
            RigOp(
                id, src, whenExpr, timing, values[parent], values[anim], flag(hand), values[pivot],
                rotationVector(values[tilt], ctx, "$path.tilt"), rotationVector(values[rest], ctx, "$path.rest"),
                steps, values[bladePart], sample, duration,
                flag(respectHitStop), flag(bodyRespectHitStop), frz, values[tip], values[root], values[edge],
            )
        }

    override fun lower(op: RigOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        val item = lc.item?.takeIf { it != "any" }
            ?: return Result.Failure(SkillDecodeError.Invalid(lc.skillId, path, "rig needs a concrete item: set the skill's item: (not any) so the rig can read content/frames/<item>.yaml"))
        val duration = when (val d = lc.requireStaticTiming(path, "for", op.duration)) {
            is Result.Success -> d.value
            is Result.Failure -> return d
        }
        val parentInstance = lc.anchors.resolve(op.parent)?.instance
            ?: return Result.Failure(SkillDecodeError.UndefinedReference(lc.skillId, path, op.parent))
        val inst = CasterMotion.instance(op, lc)
        val tilt = Vec3(op.tilt.x, op.tilt.y, op.tilt.z)
        val rest = Vec3(op.rest.x, op.rest.y, op.rest.z)

        val keys = if (op.swing.isEmpty()) {
            emptyList()
        } else {
            val part = lc.frames.item(item)?.partFor(op.bladePart)
                ?: return Result.Failure(SkillDecodeError.UndefinedReference(lc.skillId, path, op.bladePart?.let { "$item.$it" } ?: item))
            val long = part.axes["long"] ?: return Result.Failure(SkillDecodeError.Invalid(lc.skillId, path, "frames of '$item' part '${part.name}' declare no 'long' axis"))
            val edge = part.axes["edge"] ?: return Result.Failure(SkillDecodeError.Invalid(lc.skillId, path, "frames of '$item' part '${part.name}' declare no 'edge' axis"))
            when (val r = Grammar.guard { RigSwings.lower(op.swing, RigBlade(long, edge), tilt, rest, GrammarContext(lc.skillId), path) }) {
                is Result.Success -> r.value
                is Result.Failure -> return r
            }
        }
        val swingPayload: List<Map<String, Any>> = keys.map { k ->
            buildMap {
                put("t", k.tick)
                k.rotate?.let { put("rotate", Vector3d(it.x, it.y, it.z)) }
                put("ease", k.ease ?: "linear")
                put("sample", k.sample)
            }
        }
        val payload = linkedMapOf<String, Val<Any>>(
            "kind" to CasterMotion.known("rig"),
            "parent" to CasterMotion.known(parentInstance),
            "hand" to CasterMotion.known(op.hand),
            "pivot" to CasterMotion.vec3(op.pivot.toLocal()),
            "tilt" to CasterMotion.known(op.tilt),
            "rest" to CasterMotion.known(op.rest),
            "swing" to CasterMotion.known(swingPayload),
            "sample_ticks" to CasterMotion.known(op.sampleTicks.toDouble()),
            "duration" to CasterMotion.known(duration.toDouble()),
            "respect_hit_stop" to CasterMotion.known(op.respectHitStop),
            "body_respect_hit_stop" to CasterMotion.known(op.bodyRespectHitStop),
            "freezes" to CasterMotion.known(op.freezes.map { mapOf("at" to it.at.toDouble(), "ticks" to it.ticks.toDouble()) }),
        )
        op.anim?.let { payload["anim"] = CasterMotion.known(it) }
        op.tip?.let { payload["tip"] = CasterMotion.vec3(it.toLocal()) }
        op.root?.let { payload["root"] = CasterMotion.vec3(it.toLocal()) }
        op.edge?.let { payload["edge"] = CasterMotion.vec3(it.toLocal()) }
        val rows = listOf(
            Row(0, inst, Action.Effect(op.id, payload), lc.src),
            Row(duration, inst, Action.End(inst), lc.src),
        )
        return Result.Success(Lowered(rows = rows, instances = listOf(inst)))
    }
}
