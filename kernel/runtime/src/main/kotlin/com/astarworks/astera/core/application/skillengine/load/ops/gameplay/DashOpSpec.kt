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
import com.astarworks.astera.core.application.skillengine.load.grammar.Span
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.TickRange
import com.astarworks.astera.core.domain.skillengine.table.Val
import org.joml.Vector3d

/**
 * Decoded `dash` op (ADR-0303 10.1): the caster travels `speed` blocks per tick along [direction] for the span's ticks.
 * The travel is a closed-form segment; the player integrates it (no per-tick rows). The four flags are required (R9); the
 * corridor that stops or hits is a sibling `hit`, and `until: <event>` ends the dash inside its static `for` window.
 */
public class DashOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val speed: Value<Double>,
    public val ticks: Value<Double>,
    public val direction: FrameVec,
    public val ease: String?,
    public val followPitch: Boolean,
    public val stopOnBlock: Boolean,
    public val invulnerable: Boolean,
    public val faceMovement: Boolean,
) : OpNode

/** Value / id plumbing shared by the caster-motion ops (dash, reel, rig, emit). */
internal object CasterMotion {
    /** Literal -> Known; `${param}` / `= expr` -> a named live value until W1-24 evaluates them at compile time. */
    fun num(v: Value<Double>): Val<Any> = when (v) {
        is Value.Literal -> Val.Known(v.value)
        is Value.ParamRef -> Val.Live("\${" + v.name + "}", null)
        is Value.Expr -> Val.Live("= " + v.source, null)
    }

    fun vec3(v: Value<Vec3>): Val<Any> = when (v) {
        is Value.Literal -> Val.Known(Vector3d(v.value.x, v.value.y, v.value.z))
        is Value.ParamRef -> Val.Live("\${" + v.name + "}", null)
        is Value.Expr -> Val.Live("= " + v.source, null)
    }

    fun known(value: Any): Val<Any> = Val.Known(value)

    /** The op's own instance: the unroller builds it into [LowerContext.self] (ADR-0303 item 5). */
    fun instance(@Suppress("UNUSED_PARAMETER") op: OpNode, lc: LowerContext): InstanceId = lc.self

    fun row(tick: Int, instance: InstanceId, op: OpId, lc: LowerContext, payload: Map<String, Val<Any>>): Row =
        Row(tick, instance, Action.Effect(op, payload), lc.src)

    fun ticksOf(op: OpNode, ctx: GrammarContext, path: String): Value<Double> {
        val span = op.timing?.span
        return when (span) {
            is Span.Ticks -> span.ticks
            else -> ctx.fail(path, "needs 'for: <ticks>' (a fixed span)")
        }
    }
}

public object DashOpSpec : OpSpec<DashOp> {
    private val speed = Field.required("speed", Types.number, "blocks per tick along the direction")
    private val direction = Field.optional("direction", FrameVecs.type, FrameVec.Parts(Value.Literal(1.0), Value.Literal(0.0), Value.Literal(0.0)), "travel direction in the caster frame {forward, up, left}; default forward")
    private val ease = Field.optional("ease", Types.nullable(Types.id), null, "shapes the accumulated distance over the span (3.7.1); absent = linear")
    private val followPitch = Field.required("follow_pitch", Types.bool, "true = the heading follows the caster's pitch; false = level")
    private val stopOnBlock = Field.required("stop_on_block", Types.bool, "stop at a solid block")
    private val invulnerable = Field.required("invulnerable", Types.bool, "caster takes no damage while dashing")
    private val faceMovement = Field.required("face_movement", Types.bool, "turn the caster to the travel direction")

    override val name: String = "dash"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.EXTENDED
    override val fields: Fields = Fields.of("dash", speed, direction, ease, followPitch, stopOnBlock, invulnerable, faceMovement)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<DashOp, SkillDecodeError> =
        Grammar.guard {
            val path = "dash(${id.value})"
            val probe = DashOp(id, src, whenExpr, timing, values[speed], Value.Literal(0.0), values[direction], values[ease], false, true, true, false)
            val ticks = CasterMotion.ticksOf(probe, ctx, path)
            fun flag(f: Field<Value<Boolean>>): Boolean = (values[f] as? Value.Literal)?.value ?: ctx.fail("$path.${f.name}", "must be a literal true/false")
            DashOp(
                id, src, whenExpr, timing, values[speed], ticks, values[direction], values[ease],
                flag(followPitch), flag(stopOnBlock), flag(invulnerable), flag(faceMovement),
            )
        }

    override fun lower(op: DashOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        val ticks = when (val t = lc.requireStaticTiming(path, "for", op.ticks)) {
            is Result.Success -> t.value
            is Result.Failure -> return t
        }
        val local = op.direction.toLocal()
        val inst = CasterMotion.instance(op, lc)
        val payload = linkedMapOf(
            "kind" to CasterMotion.known("dash"),
            "speed" to CasterMotion.num(op.speed),
            "ticks" to CasterMotion.known(ticks.toDouble()),
            "direction" to CasterMotion.vec3(local),
            "ease" to CasterMotion.known(op.ease ?: "linear"),
            "follow_pitch" to CasterMotion.known(op.followPitch),
            "stop_on_block" to CasterMotion.known(op.stopOnBlock),
            "invulnerable" to CasterMotion.known(op.invulnerable),
            "face_movement" to CasterMotion.known(op.faceMovement),
        )
        val rows = listOf(
            CasterMotion.row(0, inst, op.id, lc, payload),
            CasterMotion.row(ticks, inst, op.id, lc, mapOf("kind" to CasterMotion.known("dash.end"))),
        )
        // `until: <event>` ends the dash at the event, inside the static `for` window: the sub-table stops the driver and ends the instance.
        val subTables = op.timing?.until?.let { ev ->
            val endRows = listOf(
                CasterMotion.row(0, inst, op.id, lc, mapOf("kind" to CasterMotion.known("dash.end"))),
                Row(0, inst, Action.End(inst), lc.src),
            )
            listOf(SubTable(EventPath(ev), lc.state, TickRange(lc.clockOffset, lc.clockOffset + ticks), 1, endRows, emptyList()))
        }.orEmpty()
        return Result.Success(Lowered(rows = rows, subTables = subTables, instances = listOf(inst)))
    }
}
