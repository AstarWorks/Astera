package com.astarworks.astera.core.application.skillengine.load.ops.presentation

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

/** Decoded `frame` op. Stub: package W1-07 replaces this file's body. */
public class FrameOp(
    override val id: OpId,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
) : OpNode

public object FrameOpSpec : OpSpec<FrameOp> {
    override val name: String = "frame"
    override val section: OpSection = OpSection.PRESENTATION
    override val timing: TimingKind? = TimingKind.EXTENDED
    override val fields: Fields = Fields.of("frame")

    override fun decode(id: OpId, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<FrameOp, SkillDecodeError> =
        TODO("W1-07: decode of op 'frame'")

    override fun lower(op: FrameOp, lc: LowerContext): Result<Lowered, SkillDecodeError> =
        TODO("W1-07: lowering of op 'frame'")
}
