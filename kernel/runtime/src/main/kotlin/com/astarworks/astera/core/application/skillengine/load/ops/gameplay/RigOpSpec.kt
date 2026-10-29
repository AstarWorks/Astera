package com.astarworks.astera.core.application.skillengine.load.ops.gameplay

import com.astarworks.astera.core.application.skillengine.compose.AnchorRegistry
import com.astarworks.astera.core.application.skillengine.compose.ExprClass
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
import com.astarworks.astera.core.application.skillengine.load.rig.FrameAxes
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
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import kotlin.math.acos
import kotlin.math.sqrt

/** One `freezes:` entry: a hit-stop window of [ticks] frozen ticks at rig-relative tick [at]. */
public data class RigFreezeSpec(val at: Int, val ticks: Int)

/**
 * Decoded `rig` op (the skin rig, ADR-0303 SKL-17): poses a part of the skill item's frame file through a rotation that follows
 * [swing] (axis direction from / via / to, edge leading or trailing). The span is the rig's lifetime. The rig is an anchor
 * instance: the part's model points are readable as `<rig id>.<point>` (`rig.tip`, `rig.root`).
 */
public class RigOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val parent: String,
    /** R27: the declared frame whose axes the swing drives (block displays anchored to it follow); null = the held item. */
    public val of: String?,
    public val anim: String?,
    /** The body point the rig rides (ADR-0303 10.5): [ARM_MOUNT] = the caster's right arm; null = the rig stands at [pivot] of its parent. */
    public val mount: String?,
    public val pivot: FrameVec,
    public val tilt: Vector3d,
    public val rest: Vector3d,
    public val swing: List<SwingStep>,
    public val part: String?,
    public val duration: Value<Double>,
    public val respectHitStop: Boolean,
    public val bodyRespectHitStop: Boolean,
    public val freezes: List<RigFreezeSpec>,
) : OpNode

public object RigOpSpec : OpSpec<RigOp> {
    private val parent = Field.required("parent", Types.id, "frame the rig hangs on")
    private val of = Field.optional("of", Types.nullable(Types.id), null, "frame id whose axes the swing drives; absent = the held item (R27)")
    private val anim = Field.optional("anim", Types.nullable(Types.id), null, "rig animation name")
    private val mount = Field.optional("mount", Types.nullable(Types.id), null, "body point the rig rides: caster.hand_r = the caster's right arm; absent = stands at the pivot of the parent")
    private val pivot = Field.optional("pivot", FrameVecs.type, FrameVec.Parts(Value.Literal(0.0), Value.Literal(0.0), Value.Literal(0.0)), "pivot offset in the parent frame")
    private val tilt = Field.optional("tilt", Rotations.type, Rotation.Turns(emptyList()), "fixed tilt of the item in the hand (literal turns)")
    private val rest = Field.optional("rest", Rotations.type, Rotation.Turns(emptyList()), "rest orientation of the model (literal turns)")
    private val swing = Field.optional("swing", RigSwings.type, emptyList(), "edge-travel steps: from / via / to, edge leading|trailing")

    private val part = Field.optional("part", Types.nullable(Types.id), null, "part of the skill item's content/frames/<item>.yaml; absent = held")
    private val respectHitStop = Field.optional("respect_hit_stop", Types.bool, Value.Literal(false), "the rig freezes during hit-stop")
    private val bodyRespectHitStop = Field.optional("body_respect_hit_stop", Types.bool, Value.Literal(false), "the body freezes during hit-stop")
    private val freezeAt = Field.required("at", Types.number, "rig-relative tick")
    private val freezeTicks = Field.required("ticks", Types.number, "frozen ticks")
    private val freezes = Field.optional(
        "freezes", Types.list(Types.nested(Fields.of("rig-freeze", freezeAt, freezeTicks))), emptyList(), "hit-stop windows",
    )

    override val name: String = "rig"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.EXTENDED
    override val fields: Fields = Fields.of(
        "rig", parent, of, anim, mount, pivot, tilt, rest, swing, part,
        respectHitStop, bodyRespectHitStop, freezes,
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
            val probe = DashOp(id, src, whenExpr, timing, Value.Literal(0.0), Value.Literal(0.0), values[pivot], null, false, true, true, false)
            val duration = CasterMotion.ticksOf(probe, ctx, path)
            fun flag(f: Field<Value<Boolean>>): Boolean = (values[f] as? Value.Literal)?.value ?: ctx.fail("$path.${f.name}", "must be a literal true/false")
            val mountPoint = values[mount]
            if (mountPoint != null && mountPoint != ARM_MOUNT) ctx.fail("$path.mount", "'$mountPoint' is not a body point a rig can ride; only $ARM_MOUNT is served")
            val steps = values[swing]
            val frz = values[freezes].mapIndexed { i, f ->
                val a = ctx.resolveNumber(f[freezeAt])?.toInt() ?: ctx.fail("$path.freezes[$i].at", "must be a number known at load")
                val t = ctx.resolveNumber(f[freezeTicks])?.toInt() ?: ctx.fail("$path.freezes[$i].ticks", "must be a number known at load")
                if (a < 0 || t < 1) ctx.fail("$path.freezes[$i]", "needs at >= 0 and ticks >= 1")
                RigFreezeSpec(a, t)
            }
            RigOp(
                id, src, whenExpr, timing, values[parent], values[of], values[anim], mountPoint, values[pivot],
                rotationVector(values[tilt], ctx, "$path.tilt"), rotationVector(values[rest], ctx, "$path.rest"),
                steps, values[part], duration,
                flag(respectHitStop), flag(bodyRespectHitStop), frz,
            )
        }

    /** The body point a rig can ride: the caster's right arm, carried by the rig's `anim` clip (the rig resolves it itself; it is not an anchor name). */
    public const val ARM_MOUNT: String = "caster.hand_r"

    /** The display transform the player draws the main-hand mesh with (`AnchorMainHand`); a rig carrying the held item takes its long/edge axes from a part with this display. */
    private const val HELD_DISPLAY = "thirdperson_righthand"

    private val BUILTIN_PARENTS = setOf("caster", "caster.body", "caster.eye", "caster.look", "world")

    override fun lower(op: RigOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        val item = lc.item?.takeIf { it != "any" }
            ?: return Result.Failure(SkillDecodeError.Invalid(lc.skillId, path, "rig needs a concrete item: set the skill's item: (not any) so the rig can read content/frames/<item>.yaml"))
        val duration = when (val d = lc.requireStaticTiming(path, "for", op.duration)) {
            is Result.Success -> d.value
            is Result.Failure -> return d
        }
        // a builtin frame is a valid rig parent as in v2: it rides the caster by name (the player resolves a non-instance parent by its text)
        val parentInstance = lc.anchors.resolve(op.parent)?.instance
            ?: op.parent.takeIf { it in BUILTIN_PARENTS }?.let { InstanceId(listOf(InstanceId.Segment(OpId(it), null))) }
            ?: return Result.Failure(SkillDecodeError.UndefinedReference(lc.skillId, path, op.parent))
        val driven = op.of?.let { name ->
            lc.anchors.resolve(name)?.instance ?: return Result.Failure(SkillDecodeError.UndefinedReference(lc.skillId, path, name))
        }
        op.anim?.let { anim ->
            val clip = lc.sprites.clipTicks(anim)
            if (clip != null && clip != duration) {
                return Result.Failure(
                    SkillDecodeError.Invalid(
                        lc.skillId, path,
                        "rig '${op.id.value}': clip '$anim' is $clip ticks but for: $duration; a rig with anim: lasts exactly its clip (write for: $clip; use detach: true when the state is shorter)",
                    ),
                )
            }
        }
        val inst = CasterMotion.instance(op, lc)
        val tilt = Vec3(op.tilt.x, op.tilt.y, op.tilt.z)
        val rest = Vec3(op.rest.x, op.rest.y, op.rest.z)

        val part = lc.frames.item(item)?.partFor(op.part)
            ?: return Result.Failure(SkillDecodeError.UndefinedReference(lc.skillId, path, op.part?.let { "$item.$it" } ?: item))
        val keys = if (op.swing.isEmpty()) {
            emptyList()
        } else {
            if (op.of == null && part.display != HELD_DISPLAY) {
                return Result.Failure(
                    SkillDecodeError.Invalid(
                        lc.skillId, path,
                        "a rig without 'of' draws the caster's main-hand item with display $HELD_DISPLAY, but part '${part.name}' of '$item' has display '${part.display}': " +
                            "its long / edge axes would not match the drawn part. Use a part declared with display: $HELD_DISPLAY (content/frames/$item.yaml)",
                    ),
                )
            }
            val long = part.axes["long"] ?: return Result.Failure(SkillDecodeError.Invalid(lc.skillId, path, "frames of '$item' part '${part.name}' declare no 'long' axis"))
            val edge = part.axes["edge"] ?: return Result.Failure(SkillDecodeError.Invalid(lc.skillId, path, "frames of '$item' part '${part.name}' declare no 'edge' axis"))
            when (val r = Grammar.guard { RigSwings.lower(op.swing, FrameAxes(long, edge), tilt, rest, GrammarContext(lc.skillId), path) }) {
                is Result.Success -> r.value
                is Result.Failure -> return r
            }
        }
        val swingPayload: List<Map<String, Any>> = keys.map { k ->
            buildMap {
                put("t", k.tick)
                k.rotate?.let { put("rotate", Vector3d(it.x, it.y, it.z)) }
                put("ease", k.ease ?: "linear")
            }
        }
        val payload = linkedMapOf<String, Val<Any>>(
            "kind" to CasterMotion.known("rig"),
            "parent" to CasterMotion.known(parentInstance),
            "pivot" to CasterMotion.vec3(op.pivot.toLocal()),
            "tilt" to CasterMotion.known(op.tilt),
            "rest" to CasterMotion.known(op.rest),
            "swing" to CasterMotion.known(swingPayload),
            "duration" to CasterMotion.known(duration.toDouble()),
            "respect_hit_stop" to CasterMotion.known(op.respectHitStop),
            "body_respect_hit_stop" to CasterMotion.known(op.bodyRespectHitStop),
            "freezes" to CasterMotion.known(op.freezes.map { mapOf("at" to it.at.toDouble(), "ticks" to it.ticks.toDouble()) }),
        )
        driven?.let { payload["of"] = CasterMotion.known(it) }
        op.anim?.let { payload["anim"] = CasterMotion.known(it) }
        op.mount?.let { payload["mount"] = CasterMotion.known(it) }
        if (op.of == null) {
            // the rig is an anchor instance (ADR-0303 10.5): the part's model points ride in the table like a frame's (`rig.tip`, `rig.root`);
            // with `of` the driven frame already is the anchor and keeps its own points
            part.points.forEach { (name, p) -> payload["point.$name"] = CasterMotion.known(Vector3d(p.x, p.y, p.z)) }
            (lc.anchors as? AnchorRegistry)?.declare(InstanceId(inst.path.dropLast(1) + InstanceId.Segment(op.id, null)), null, ExprClass.L)
        }
        val rows = listOf(
            Row(0, inst, Action.Effect(op.id, payload, spawnsAnchor = true), lc.src),
            Row(duration, inst, Action.End(inst), lc.src),
        )
        return Result.Success(Lowered(rows = rows, instances = listOf(inst)))
    }
}
