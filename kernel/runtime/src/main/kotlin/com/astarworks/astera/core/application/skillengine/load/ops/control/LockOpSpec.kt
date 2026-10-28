package com.astarworks.astera.core.application.skillengine.load.ops.control

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
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
import com.astarworks.astera.core.domain.skillengine.model.ParamValue
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Row

/** Decoded `lock` op (10.6): freezes caster move / pitch / yaw for the timing window. */
public class LockOp(
    override val id: OpId,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val move: Boolean,
    public val pitch: Boolean,
    public val yaw: Boolean,
) : OpNode

public object LockOpSpec : OpSpec<LockOp> {
    private val move = Field.optional("move", Types.bool, Value.Literal(false), "true: the caster cannot walk or jump for the window")
    private val pitch = Field.optional("pitch", Types.bool, Value.Literal(false), "true: look pitch held at its value on the first tick")
    private val yaw = Field.optional("yaw", Types.bool, Value.Literal(false), "true: look yaw held at its value on the first tick")

    override val name: String = "lock"
    override val section: OpSection = OpSection.CONTROL
    override val timing: TimingKind? = TimingKind.EXTENDED
    override val fields: Fields = Fields.of("lock", move, pitch, yaw)

    override fun decode(id: OpId, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<LockOp, SkillDecodeError> =
        Grammar.guard {
            val path = "lock[${id.value}]"
            fun flag(f: Field<Value<Boolean>>): Boolean = when (val v = values[f]) {
                is Value.Literal -> v.value
                is Value.ParamRef -> (ctx.params?.get(v.name) as? ParamValue.Flag)?.value
                    ?: ctx.fail("$path.${f.name}", "'\${${v.name}}' must be a boolean parameter")
                is Value.Expr -> ctx.fail("$path.${f.name}", "a lock field is fixed at load; use true or false")
            }
            val m = flag(move)
            val p = flag(pitch)
            val y = flag(yaw)
            if (!m && !p && !y) ctx.fail(path, "a lock must set at least one of move, pitch, yaw to true")
            if (timing == null || (timing.span == null && timing.until == null)) {
                ctx.fail(path, "a lock needs for or until: the window ends it, there is no unlock op")
            }
            LockOp(id, whenExpr, timing, m, p, y)
        }

    override fun lower(op: LockOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val self = InstanceId(lc.instancePrefix.path + InstanceId.Segment(op.id, null))
        val span = op.timing?.span
        val path = lc.src.value
        // untilTick is state-relative like clockOffset (the unroller shifts rows but not payloads).
        val ticks: Int? = when (span) {
            is Span.Ticks -> when (val r = lc.requireStaticTiming("$path.for", "for", span.ticks)) {
                is Result.Success -> r.value
                is Result.Failure -> return r
            }
            else -> null // `for: cast` or `until: <event>`: held until the owning state exits, the cast ends or is cancelled
        }
        if (ticks != null && ticks < 1) {
            return Result.Failure(SkillDecodeError.Invalid(lc.skillId, "$path.for", "a lock window must last at least 1 tick"))
        }
        val untilTick = if (ticks != null) lc.clockOffset + ticks else lc.config.maxCastTicks
        val rows = mutableListOf(Row(0, self, Action.Lock(op.move, op.pitch, op.yaw, untilTick), lc.src))
        if (ticks != null) rows += Row(ticks, self, Action.End(self), lc.src)
        return Result.Success(Lowered(rows = rows, instances = listOf(self)))
    }
}
