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

/** Decoded `particle_def` op. Stub: package W1-04 replaces this file's body. */
public class ParticleDefOp(
    override val id: OpId,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
) : OpNode

public object ParticleDefOpSpec : OpSpec<ParticleDefOp> {
    override val name: String = "particle_def"
    override val section: OpSection = OpSection.PRESENTATION
    override val timing: TimingKind? = null
    override val fields: Fields = Fields.of("particle_def")

    override fun decode(id: OpId, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<ParticleDefOp, SkillDecodeError> =
        TODO("W1-04: decode of op 'particle_def'")

    override fun lower(op: ParticleDefOp, lc: LowerContext): Result<Lowered, SkillDecodeError> =
        TODO("W1-04: lowering of op 'particle_def'")
}
