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

/** Decoded `lock` op. Stub: package W1-12 replaces this file's body. */
public class LockOp(
    override val id: OpId,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
) : OpNode

public object LockOpSpec : OpSpec<LockOp> {
    override val name: String = "lock"
    override val section: OpSection = OpSection.CONTROL
    override val timing: TimingKind? = TimingKind.EXTENDED
    override val fields: Fields = Fields.of("lock")

    override fun decode(id: OpId, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<LockOp, SkillDecodeError> =
        TODO("W1-12: decode of op 'lock'")

    override fun lower(op: LockOp, lc: LowerContext): Result<Lowered, SkillDecodeError> =
        TODO("W1-12: lowering of op 'lock'")
}
