package com.astarworks.astera.core.application.skillengine.load.ops.presentation

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
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.GameplayOps
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
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
 * Decoded `held_model` op (ADR-0303 rev4.6): shows the item model [model] on the weapon the caster holds for the span, then puts the
 * item's own model back. The swap ends with the op's instance, so `for: N` lowers to an `End` row at tick N; `until: <event>` ends it at the event.
 */
public class HeldModelOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    val model: String,
) : OpNode

public object HeldModelOpSpec : OpSpec<HeldModelOp> {
    private val modelField = Field.required("model", Types.id, "item model id shown on the held weapon (`namespace:path`) while the op lives")

    override val name: String = "held_model"
    override val section: OpSection = OpSection.PRESENTATION
    override val timing: TimingKind? = TimingKind.EXTENDED
    override val fields: Fields = Fields.of("held_model", modelField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<HeldModelOp, SkillDecodeError> =
        if (timing?.span == null && timing?.until == null) {
            Result.Failure(SkillDecodeError.MissingField(ctx.file, "held_model(${id.value})", "for"))
        } else {
            Result.Success(HeldModelOp(id, src, whenExpr, timing, values[modelField]))
        }

    override fun lower(op: HeldModelOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        val start = Row(
            0, lc.self,
            Action.Effect(OpId("held_model"), mapOf<String, Val<Any>>("model" to Val.Known(op.model))),
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
            listOf(SubTable(EventPath(ev), lc.state, TickRange(lc.clockOffset, maxOf(lc.clockOffset, hi)), 1, listOf(Row(0, lc.self, Action.End(lc.self), lc.src)), emptyList()))
        }.orEmpty()
        return Result.Success(Lowered(rows = rows, subTables = subTables, instances = listOf(lc.self)))
    }
}
