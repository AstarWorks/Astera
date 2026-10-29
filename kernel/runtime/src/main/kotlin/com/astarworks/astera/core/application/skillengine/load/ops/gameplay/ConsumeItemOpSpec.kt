package com.astarworks.astera.core.application.skillengine.load.ops.gameplay

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Val

/** Decoded `consume_item` op (R25): takes [count] of the Astera item [item] from the target's inventory. */
public class ConsumeItemOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    val target: TargetRef,
    val item: String,
    val count: Value<Double>,
) : OpNode

public object ConsumeItemOpSpec : OpSpec<ConsumeItemOp> {
    override val name: String = "consume_item"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT

    private val targetField = Field.optional("target", GameplayOps.target, TargetRef.Caster, "victim, caster (default) or an area")
    private val itemField = Field.required("item", Types.id, "Astera item id to take")
    private val countField = Field.required("count", Types.number, "how many to take (at least 1)")

    override val fields: Fields = Fields.of("consume_item", targetField, itemField, countField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<ConsumeItemOp, SkillDecodeError> =
        Result.Success(ConsumeItemOp(id, src, whenExpr, timing, values[targetField], values[itemField], values[countField]))

    override fun lower(op: ConsumeItemOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        return GameplayOps.targetCell(op.target, lc, path).flatMap { target ->
            GameplayOps.whole(op.count, lc, "$path.count").flatMap { count ->
                if (count < 1) {
                    Result.Failure(SkillDecodeError.Invalid(lc.skillId, "$path.count", "count must be at least 1, found $count"))
                } else {
                    Result.Success(
                        GameplayOps.effect(
                            lc, "consume_item",
                            mapOf<String, Val<Any>>("target" to target, "item" to Val.Known(op.item), "count" to Val.Known(count.toDouble())),
                        ),
                    )
                }
            }
        }
    }
}
