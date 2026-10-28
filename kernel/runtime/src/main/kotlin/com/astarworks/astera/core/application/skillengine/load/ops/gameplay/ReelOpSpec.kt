package com.astarworks.astera.core.application.skillengine.load.ops.gameplay

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
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Val

/**
 * Decoded `reel` op (ADR-0303 10.1): the frame [anchor] is pulled toward the point [to] at [speed] blocks per tick.
 * Reaching it produces the event `<id>.end` (R14: `arrive` is the `end` of the reel part).
 */
public class ReelOp(
    override val id: OpId,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val anchor: String,
    public val to: String,
    public val speed: Value<Double>,
    public val ease: String?,
    public val ticks: Value<Double>?,
) : OpNode

public object ReelOpSpec : OpSpec<ReelOp> {
    private val anchor = Field.required("anchor", Types.id, "the frame that is pulled")
    private val to = Field.required("to", Types.id, "the frame or point it is pulled toward")
    private val speed = Field.required("speed", Types.number, "blocks per tick, > 0")
    private val ease = Field.optional("ease", Types.nullable(Types.enumOf("in", "out")), null, "in = start slow and speed up, out = start fast and slow down")

    override val name: String = "reel"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.EXTENDED
    override val fields: Fields = Fields.of("reel", anchor, to, speed, ease)

    override fun decode(id: OpId, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<ReelOp, SkillDecodeError> =
        Grammar.guard {
            val path = "reel(${id.value})"
            val s = values[speed]
            if (s is Value.Literal && s.value <= 0.0) ctx.fail("$path.speed", "must be > 0")
            val span = timing?.span
            val ticks = when (span) {
                null -> null
                is Span.Ticks -> span.ticks
                Span.UntilCastEnds -> ctx.fail(path, "a reel ends on arrival; 'for: cast' is not allowed")
            }
            ReelOp(id, whenExpr, timing, values[anchor], values[to], s, values[ease], ticks)
        }

    override fun lower(op: ReelOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val inst = CasterMotion.instance(op, lc)
        val frame = lc.anchors.resolve(op.anchor)?.instance
        val target = lc.anchors.resolve(op.to)?.instance
        if (frame == null) return Result.Failure(SkillDecodeError.UndefinedReference(lc.skillId, lc.src.value, op.anchor))
        if (target == null) return Result.Failure(SkillDecodeError.UndefinedReference(lc.skillId, lc.src.value, op.to))
        val payload = mapOf<String, Val<Any>>(
            "kind" to CasterMotion.known("reel"),
            "frame" to CasterMotion.known(frame),
            "to" to CasterMotion.known(target),
            "speed" to CasterMotion.num(op.speed),
            "ease" to CasterMotion.known(op.ease ?: "linear"),
        )
        val rows = mutableListOf(CasterMotion.row(0, inst, op.id, lc, payload))
        val ticks = op.ticks?.let { t ->
            when (val r = lc.requireStaticTiming(lc.src.value, "for", t)) {
                is Result.Success -> r.value
                is Result.Failure -> return r
            }
        }
        if (ticks != null) rows += CasterMotion.row(ticks, inst, op.id, lc, mapOf("kind" to CasterMotion.known("reel.stop")))
        val endEvent = EventPath(op.id.value + ".end")
        return Result.Success(Lowered(rows = rows, instances = listOf(inst), events = listOf(endEvent)))
    }
}
