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
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Val

/**
 * Decoded `freeze` op (R32): holds the listed entities in place for [ticks] ticks. It freezes entities, never the skill
 * clock (the lock GAP in the v2 couture-rapier-s2 draft did the wrong thing). Scope entries: `caster`, `event.victim`,
 * or an anchor name whose instance is held.
 */
public class FreezeOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    val ticks: Value<Double>,
    val scope: List<String>,
) : OpNode

public object FreezeOpSpec : OpSpec<FreezeOp> {
    override val name: String = "freeze"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT

    private val ticksField = Field.required("ticks", Types.number, "how long the entities are held (at least 1)")
    private val scopeField = Field.required("scope", Types.list(Types.id), "caster, event.victim or anchor names")

    override val fields: Fields = Fields.of("freeze", ticksField, scopeField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<FreezeOp, SkillDecodeError> =
        Grammar.guard {
            val scope = values[scopeField]
            if (scope.isEmpty()) ctx.fail("freeze(${id.value}).scope", "scope must name at least one entity")
            FreezeOp(id, src, whenExpr, timing, values[ticksField], scope)
        }

    override fun lower(op: FreezeOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        val entries = ArrayList<String>(op.scope.size)
        for (name in op.scope) {
            when (name) {
                "caster", "event.victim" -> entries += name
                else -> when (val r = GameplayOps.anchor(name, lc, "$path.scope")) {
                    is Result.Failure -> return r
                    is Result.Success -> entries += "instance:" + r.value.text
                }
            }
        }
        return GameplayOps.whole(op.ticks, lc, "$path.ticks").flatMap { ticks ->
            if (ticks < 1) {
                Result.Failure(SkillDecodeError.Invalid(lc.skillId, "$path.ticks", "ticks must be at least 1, found $ticks"))
            } else {
                Result.Success(
                    GameplayOps.effect(
                        lc, "freeze",
                        mapOf<String, Val<Any>>("ticks" to Val.Known(ticks.toDouble()), "scope" to Val.Known(entries)),
                    ),
                )
            }
        }
    }
}
