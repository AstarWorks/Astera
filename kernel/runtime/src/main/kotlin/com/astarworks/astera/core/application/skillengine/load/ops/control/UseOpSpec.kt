package com.astarworks.astera.core.application.skillengine.load.ops.control

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

/** Decoded `use` op. Stub: package W1-14 replaces this file's body. */
public class UseOp(
    override val id: OpId,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
) : OpNode

public object UseOpSpec : OpSpec<UseOp> {
    override val name: String = "use"
    override val section: OpSection = OpSection.CONTROL
    override val timing: TimingKind? = TimingKind.POINT
    override val fields: Fields = Fields.of("use")

    override fun decode(id: OpId, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<UseOp, SkillDecodeError> =
        TODO("W1-14: decode of op 'use'")

    override fun lower(op: UseOp, lc: LowerContext): Result<Lowered, SkillDecodeError> =
        TODO("W1-14: lowering of op 'use'")
}
