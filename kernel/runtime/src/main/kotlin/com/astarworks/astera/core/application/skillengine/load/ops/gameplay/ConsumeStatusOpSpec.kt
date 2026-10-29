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
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Val

/** Decoded `consume_status` op (R24): removes the status [status] from the target (the cost half of a status-gated skill). */
public class ConsumeStatusOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    val target: TargetRef,
    val status: String,
) : OpNode

public object ConsumeStatusOpSpec : OpSpec<ConsumeStatusOp> {
    override val name: String = "consume_status"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT

    private val targetField = Field.optional("target", GameplayOps.target, TargetRef.Caster, "victim, caster (default) or an area")
    private val statusField = Field.required("status", Types.id, "status id to remove")

    override val fields: Fields = Fields.of("consume_status", targetField, statusField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<ConsumeStatusOp, SkillDecodeError> =
        Result.Success(ConsumeStatusOp(id, src, whenExpr, timing, values[targetField], values[statusField]))

    override fun lower(op: ConsumeStatusOp, lc: LowerContext): Result<Lowered, SkillDecodeError> =
        GameplayOps.statusId(lc, lc.src.value, "status", op.status).flatMap { _ -> GameplayOps.targetCell(op.target, lc, lc.src.value) }.map { target ->
            GameplayOps.effect(lc, "consume_status", mapOf<String, Val<Any>>("target" to target, "status" to Val.Known(op.status)))
        }
}
