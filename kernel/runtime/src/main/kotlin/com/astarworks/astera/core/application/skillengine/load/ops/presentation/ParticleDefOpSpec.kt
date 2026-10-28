package com.astarworks.astera.core.application.skillengine.load.ops.presentation

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
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath

/** Decoded `particle_def` op: a custom particle definition (ADR-0303 2.2) emitted at its anchor, timed like `particle`. */
public class ParticleDefOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val def: String,
    public val anchor: ParticleAnchor,
) : OpNode

public object ParticleDefOpSpec : OpSpec<ParticleDefOp> {
    private val DEF = Field.required("def", Types.id, "id of a custom particle definition (content/particles)")
    private val ANCHOR = Field.required("anchor", ParticleAnchorType, "emission point: anchor name or {of, sweep}")

    override val name: String = "particle_def"
    override val section: OpSection = OpSection.PRESENTATION
    /** ADR-0303 2.0 lists `particle_def` among the point kinds that carry the shared timing block. */
    override val timing: TimingKind? = TimingKind.POINT
    override val fields: Fields = Fields.of("particle_def", DEF, ANCHOR)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<ParticleDefOp, SkillDecodeError> =
        Grammar.guard {
            ParticleDefOp(id, src, whenExpr, timing, def = values[DEF], anchor = values[ANCHOR])
        }

    /** Action has no particle-definition variant yet (W0-01 request), so the op cannot be lowered without a placeholder. */
    override fun lower(op: ParticleDefOp, lc: LowerContext): Result<Lowered, SkillDecodeError> =
        Result.Failure(
            SkillDecodeError.Invalid(
                lc.skillId, lc.src.value,
                "particle_def '${op.def}': Action has no ParticleDef variant (W0-01 request); not lowered",
            ),
        )

}
