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
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Val

/** Decoded `modify_attribute` op: a timed attribute change on a target (ADR-0303 10.1b). */
public class ModifyAttributeOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    val target: TargetRef,
    val attribute: String,
    /** `add`, `multiply_base` or `multiply_total`. */
    val operation: String,
    val amount: Value<Double>,
) : OpNode

public object ModifyAttributeOpSpec : OpSpec<ModifyAttributeOp> {
    override val name: String = "modify_attribute"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT

    private val targetField = Field.required("target", GameplayOps.target, "victim, caster or an area")
    private val attributeField = Field.required("attribute", Types.id, "attribute id, e.g. resist.physical")
    private val operationField = Field.required("operation", Types.enumOf("add", "multiply_base", "multiply_total"), "how amount combines")
    private val amountField = Field.required("amount", Types.number, "the value combined")

    override val fields: Fields = Fields.of("modify_attribute", targetField, attributeField, operationField, amountField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<ModifyAttributeOp, SkillDecodeError> =
        Result.Success(
            ModifyAttributeOp(
                id = id,
                src = src,
                whenExpr = whenExpr,
                timing = timing,
                target = values[targetField],
                attribute = values[attributeField],
                operation = values[operationField],
                amount = values[amountField],
            ),
        )

    override fun lower(op: ModifyAttributeOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        return GameplayOps.attributeKey(lc, path, op.attribute).flatMap { _ -> GameplayOps.targetCell(op.target, lc, path) }.flatMap { target ->
            GameplayOps.duration(op.timing, lc, path, "for").flatMap { forTicks ->
                run {
                    val amount = GameplayOps.live(op.amount)
                    Result.Success(GameplayOps.effect(
                        lc,
                        "modify_attribute",
                        mapOf<String, Val<Any>>(
                            "target" to target,
                            "attribute" to Val.Known(op.attribute),
                            "operation" to Val.Known(op.operation),
                            "amount" to amount,
                            "for" to Val.Known(forTicks),
                        ),
                    ))
                }
            }
        }
    }
}
