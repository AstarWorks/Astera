package com.astarworks.astera.core.application.skillengine.load.ops.presentation

import com.astarworks.astera.core.application.skillengine.compose.AnchorRegistry
import com.astarworks.astera.core.application.skillengine.compose.ExprClass
import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.Span
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.CasterMotion
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.GameplayOps
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.TickRange
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Val

/**
 * Decoded `body` op (R39 / R41): plays the body animation clip [anim] on [target] (the caster, or the `event.victim` / `event.attacker` of the event
 * the op runs in) at [speed] for the span. The clip ends
 * with the op's instance, so `for: N` lowers to an `End` row at tick N; `for: cast` ends with the cast. `until: <event>`
 * ends it at the event (the player ends the instance, not this lowering).
 */
public class BodyOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    val anim: String,
    val speed: Value<Double>,
    val target: TargetRef,
) : OpNode

public object BodyOpSpec : OpSpec<BodyOp> {
    private val animField = Field.required("anim", Types.id, "body animation clip id")
    private val targetField = Field.required("target", GameplayOps.target, "whose body plays the clip: caster | event.victim | event.attacker (one entity, never an area)")
    private val speedField = Field.optional("speed", Types.number, Value.Literal(1.0), "playback speed, 1.0 = authored")

    override val name: String = "body"
    override val section: OpSection = OpSection.PRESENTATION
    override val timing: TimingKind? = TimingKind.EXTENDED
    override val fields: Fields = Fields.of("body", animField, speedField, targetField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<BodyOp, SkillDecodeError> =
        if (timing?.span == null && timing?.until == null) {
            Result.Failure(SkillDecodeError.MissingField(ctx.file, "body(${id.value})", "for"))
        } else if (values[targetField] !is TargetRef.Caster && values[targetField] !is TargetRef.Victim && values[targetField] !is TargetRef.Attacker) {
            Result.Failure(SkillDecodeError.Invalid(ctx.file, "body(${id.value}).target", "a body clip plays on one entity: caster, event.victim or event.attacker, not an area"))
        } else {
            Result.Success(BodyOp(id, src, whenExpr, timing, values[animField], values[speedField], values[targetField]))
        }

    override fun lower(op: BodyOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        val target = when (val t = GameplayOps.targetCell(op.target, lc, path)) {
            is Result.Failure -> return t
            is Result.Success -> t.value
        }
        val start = Row(
            0, lc.self,
            Action.Effect(OpId("body"), mapOf<String, Val<Any>>("anim" to Val.Known(op.anim), "speed" to CasterMotion.num(op.speed), "target" to target)),
            lc.src,
        )
        var end: Int? = null
        val rows = when (op.timing?.span) {
            is Span.Ticks -> GameplayOps.duration(op.timing, lc, path, "for").let { d ->
                when (d) {
                    is Result.Failure -> return d
                    is Result.Success -> { end = d.value; listOf(start, Row(d.value, lc.self, Action.End(lc.self), lc.src)) }
                }
            }
            else -> listOf(start)
        }
        // `until` is a mask (2.0), like a display's: the event's sub-table ends the clip the first time the event fires.
        val subTables = op.timing?.until?.let { ev ->
            val hi = lc.clockOffset + (end ?: lc.config.maxLifeTicks)
            listOf(SubTable(EventPath(ev), lc.state, TickRange(lc.clockOffset, maxOf(lc.clockOffset, hi)), 1, listOf(Row(0, lc.self, Action.End(lc.self), lc.src)), emptyList(), mask = true))
        }.orEmpty()
        // the body is an instance of the caster's own rig: `<id>.<point>` names a point of its clip (the shared skeleton, `content/frames/body.yaml`)
        (lc.anchors as? AnchorRegistry)?.declare(lc.self, null, ExprClass.L)
        return Result.Success(Lowered(rows = rows, subTables = subTables, instances = listOf(lc.self)))
    }
}
