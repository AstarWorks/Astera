package com.astarworks.astera.core.application.skillengine.load.ops.gameplay

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.Grammar
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Row

/**
 * Decoded `emit` op (ADR-0303 10.1): fires the named event of the enclosing part / state machine. Written `{emit: hit}` or
 * `{op: emit, name: hit}`. Inside a group or part the event is also listed under its qualified path (`<prefix>.<name>`).
 */
public class EmitOp(
    override val id: OpId,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val event: String,
) : OpNode

public object EmitOpSpec : OpSpec<EmitOp> {
    private val nameField = Field.optional("name", Types.nullable(Types.id), null, "event to emit")
    private val shorthand = Field.optional("emit", Types.nullable(Types.id), null, "shorthand for 'op: emit, name: <event>'")

    override val name: String = "emit"
    override val section: OpSection = OpSection.CONTROL
    override val timing: TimingKind? = TimingKind.POINT
    override val fields: Fields = Fields.of("emit", nameField, shorthand)

    override fun decode(id: OpId, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<EmitOp, SkillDecodeError> =
        Grammar.guard {
            val path = "emit(${id.value})"
            val a = values[nameField]
            val b = values[shorthand]
            if (a != null && b != null && a != b) ctx.fail(path, "'name' and 'emit' disagree ('$a' vs '$b')")
            val event = a ?: b ?: ctx.fail(path, "needs the event name: {emit: <name>} or {op: emit, name: <name>}")
            EmitOp(id, whenExpr, timing, event)
        }

    override fun lower(op: EmitOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val inst = CasterMotion.instance(op, lc)
        val row = Row(0, inst, Action.Emit(EventPath(op.event)), lc.src)
        val prefix = lc.instancePrefix.text
        val qualified = if (prefix.isEmpty()) emptyList() else listOf(EventPath("$prefix.${op.event}"))
        return Result.Success(Lowered(rows = listOf(row), events = listOf(EventPath(op.event)) + qualified))
    }
}
