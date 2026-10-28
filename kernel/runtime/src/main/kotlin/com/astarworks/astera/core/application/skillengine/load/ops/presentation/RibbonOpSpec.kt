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

/** Decoded `ribbon` op. Stub: package W1-06 replaces this file's body. */
public class RibbonOp(
    override val id: OpId,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
) : OpNode

public object RibbonOpSpec : OpSpec<RibbonOp> {
    override val name: String = "ribbon"
    override val section: OpSection = OpSection.PRESENTATION
    override val timing: TimingKind? = TimingKind.EXTENDED
    override val fields: Fields = Fields.of("ribbon")

    override fun decode(id: OpId, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<RibbonOp, SkillDecodeError> =
        TODO("W1-06: decode of op 'ribbon'")

    override fun lower(op: RibbonOp, lc: LowerContext): Result<Lowered, SkillDecodeError> =
        TODO("W1-06: lowering of op 'ribbon'")
}
