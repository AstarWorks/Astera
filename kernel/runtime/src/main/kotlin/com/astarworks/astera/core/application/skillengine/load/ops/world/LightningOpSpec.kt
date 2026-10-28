package com.astarworks.astera.core.application.skillengine.load.ops.world

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.GameplayOps
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Val

/** Decoded `lightning` (ADR-0303 10.1c): [visualOnly] = vanilla `strikeLightningEffect`, otherwise a damaging strike. */
public class LightningOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val anchor: String,
    public val visualOnly: Boolean,
) : OpNode

public object LightningOpSpec : OpSpec<LightningOp> {
    private val ANCHOR = WorldOps.anchorField
    private val VISUAL_ONLY: Field<Boolean> = Field.required("visual_only", GameplayOps.flag, "true = effect only, false = damaging strike")

    override val name: String = "lightning"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT
    override val fields: Fields = Fields.of("lightning", ANCHOR, VISUAL_ONLY)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<LightningOp, SkillDecodeError> =
        Result.Success(LightningOp(id, src, whenExpr, timing, values[ANCHOR], values[VISUAL_ONLY]))

    override fun lower(op: LightningOp, lc: LowerContext): Result<Lowered, SkillDecodeError> =
        WorldOps.lower(OpId(name), op.anchor, mapOf<String, Val<Any>>("visual_only" to Val.Known(op.visualOnly)), lc)
}
