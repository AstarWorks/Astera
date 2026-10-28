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

/** Decoded `resource` op: restores [amount] of a named resource (0301 resource vocabulary) on the target. */
public class ResourceOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    val target: TargetRef,
    val resource: String,
    val amount: Value<Double>,
) : OpNode

public object ResourceOpSpec : OpSpec<ResourceOp> {
    override val name: String = "resource"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT

    private val targetField = Field.required("target", GameplayOps.target, "victim, caster or an area")
    private val resourceField = Field.required("resource", Types.id, "resource id")
    private val amountField = Field.required("amount", Types.number, "amount restored")

    override val fields: Fields = Fields.of("resource", targetField, resourceField, amountField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<ResourceOp, SkillDecodeError> =
        Result.Success(ResourceOp(id, src, whenExpr, timing, values[targetField], values[resourceField], values[amountField]))

    override fun lower(op: ResourceOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        return GameplayOps.targetCell(op.target, lc, path).flatMap { target ->
            GameplayOps.literal(op.amount, lc, "$path.amount").map { amount ->
                GameplayOps.effect(
                    lc,
                    "resource",
                    mapOf<String, Val<Any>>("target" to target, "resource" to Val.Known(op.resource), "amount" to Val.Known(amount)),
                )
            }
        }
    }
}
