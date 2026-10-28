package com.astarworks.astera.core.application.skillengine.load.ops.gameplay

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
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

/** Decoded `cleanse` op (R25): removes statuses from the target; `negative_only` is required (the v2 default made explicit). */
public class CleanseOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    val target: TargetRef,
    val negativeOnly: Boolean,
) : OpNode

public object CleanseOpSpec : OpSpec<CleanseOp> {
    override val name: String = "cleanse"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT

    private val targetField = Field.optional("target", GameplayOps.target, TargetRef.Caster, "victim, caster (default) or an area")
    private val negativeField = Field.required("negative_only", GameplayOps.flag, "true = only harmful statuses, false = every status")

    override val fields: Fields = Fields.of("cleanse", targetField, negativeField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<CleanseOp, SkillDecodeError> =
        Result.Success(CleanseOp(id, src, whenExpr, timing, values[targetField], values[negativeField]))

    override fun lower(op: CleanseOp, lc: LowerContext): Result<Lowered, SkillDecodeError> =
        GameplayOps.targetCell(op.target, lc, lc.src.value).map { target ->
            GameplayOps.effect(lc, "cleanse", mapOf<String, Val<Any>>("target" to target, "negative_only" to Val.Known(op.negativeOnly)))
        }
}
