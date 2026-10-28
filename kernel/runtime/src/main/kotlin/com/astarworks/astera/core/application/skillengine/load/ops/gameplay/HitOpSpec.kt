package com.astarworks.astera.core.application.skillengine.load.ops.gameplay

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
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
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.OpId

/** Decoded `hit` op. Stub: package W1-10 replaces this file's body. */
public class HitOp(
    override val id: OpId,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
) : OpNode

public object HitOpSpec : OpSpec<HitOp> {
    override val name: String = "hit"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT
    override val fields: Fields = Fields.of("hit")

    override fun decode(id: OpId, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<HitOp, SkillDecodeError> =
        TODO("W1-10: decode of op 'hit'")

    override fun lower(op: HitOp, lc: LowerContext): Result<Lowered, SkillDecodeError> =
        TODO("W1-10: lowering of op 'hit'")
}
