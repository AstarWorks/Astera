package com.astarworks.astera.core.application.skillengine.load.ops.gameplay

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.Grammar
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarSource
import com.astarworks.astera.core.application.skillengine.load.grammar.OffsetVec
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
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Val

/** Decoded `blink` op (ADR-0303 3.1): the caster is moved to [to] in one tick, never into a block or a body. */
public class BlinkOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val to: OffsetVec,
    public val max: Double,
    public val keepLook: Boolean,
    public val invulnerableTicks: Int,
) : OpNode

public object BlinkOpSpec : OpSpec<BlinkOp> {
    /** Longest blink a skill may declare (blocks). */
    public const val MAX_BLINK_BLOCKS: Double = 16.0

    private val to = Field.required("to", Types.offsetVec, "the destination point: {of: <anchor>, forward, up, left} from that anchor's position along its axes at the row tick (an `of: caster` blink is level), or a world CEL vec3")
    private val max = Field.required("max", Types.number, "longest travel in blocks (a literal, at most $MAX_BLINK_BLOCKS); a farther destination is cut short along the line")
    private val keepLook = Field.required("keep_look", Types.bool, "true = the caster keeps yaw and pitch; false = turn to the travel direction")
    private val invulnerable = Field.optional("invulnerable", Types.number, Value.Literal(0.0), "ticks of damage immunity after arriving (0 = none)")

    override val name: String = "blink"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT
    override val fields: Fields = Fields.of("blink", to, max, keepLook, invulnerable)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<BlinkOp, SkillDecodeError> =
        Grammar.guard {
            val path = "blink(${id.value})"
            val m = (values[max] as? Value.Literal)?.value ?: ctx.fail("$path.max", "must be a literal number of blocks")
            if (m <= 0.0 || m > MAX_BLINK_BLOCKS) ctx.fail("$path.max", "must be in (0, $MAX_BLINK_BLOCKS] blocks, found $m")
            val keep = (values[keepLook] as? Value.Literal)?.value ?: ctx.fail("$path.keep_look", "must be a literal true/false")
            val inv = (values[invulnerable] as? Value.Literal)?.value ?: ctx.fail("$path.invulnerable", "must be a literal number of ticks")
            if (inv < 0.0 || inv > 100.0 || inv % 1.0 != 0.0) ctx.fail("$path.invulnerable", "must be a whole number of ticks in [0, 100], found $inv")
            BlinkOp(id, src, whenExpr, timing, values[to], m, keep, inv.toInt())
        }

    override fun lower(op: BlinkOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        return destination(op.to, lc, "$path.to").map { dest ->
            GameplayOps.effect(
                lc, "blink",
                mapOf<String, Val<Any>>(
                    "to" to dest,
                    "max" to Val.Known(op.max),
                    "keep_look" to Val.Known(op.keepLook),
                    "invulnerable" to Val.Known(op.invulnerableTicks.toDouble()),
                ),
            )
        }
    }

    private fun destination(v: OffsetVec, lc: LowerContext, path: String): Result<Val<Any>, SkillDecodeError> = when (v) {
        is OffsetVec.World -> Result.Success(GameplayOps.liveVec(v.value))
        is OffsetVec.InAnchor -> {
            val name = v.of.of
            if (name !in V3Bindings.BUILTIN_FRAMES && lc.anchors.resolve(name) == null && lc.anchors.document(name) == null) {
                Result.Failure(SkillDecodeError.UndefinedReference(lc.skillId, "$path.of", name))
            } else {
                val terms = listOf("forward" to v.parts.forward, "up" to v.parts.up, "left" to v.parts.left)
                    .filterNot { (_, n) -> n is Value.Literal && n.value == 0.0 }
                    .map { (axis, n) -> "$axis($name) * ${GrammarSource.of(n)}" }
                Result.Success(Val.Live("= " + (listOf("pos($name)") + terms).joinToString(" + "), null))
            }
        }
    }
}
