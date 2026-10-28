package com.astarworks.astera.core.application.skillengine.load.ops.presentation

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.AnchorRef
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVec
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVecs
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
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SrcPath

/** Decoded `particle_def` op: a custom particle definition (ADR-0303 2.2) emitted at its anchor, timed like `particle`. */
public class ParticleDefOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val def: String,
    public val anchor: AnchorRef,
    public val shift: FrameVec?,
) : OpNode

public object ParticleDefOpSpec : OpSpec<ParticleDefOp> {
    private val DEF = Field.required("def", Types.id, "id of a custom particle definition (content/particles)")
    private val ANCHOR = Field.required("anchor", Types.anchorRef, "emission point: anchor name or {of, offset, sweep}")
    private val SHIFT = Field.optional("shift", Types.nullable(FrameVecs.type), null, "per-copy displacement of the emission point: {forward, up, left} in the anchor's axes, or a whole world vec; reads i, n, draw, roll")

    override val name: String = "particle_def"
    override val section: OpSection = OpSection.PRESENTATION
    /** ADR-0303 2.0 lists `particle_def` among the point kinds that carry the shared timing block. */
    override val timing: TimingKind? = TimingKind.POINT
    override val fields: Fields = Fields.of("particle_def", DEF, ANCHOR, SHIFT)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<ParticleDefOp, SkillDecodeError> =
        Grammar.guard {
            ParticleDefOp(id, src, whenExpr, timing, def = values[DEF], anchor = values[ANCHOR], shift = values[SHIFT])
        }

    override fun lower(op: ParticleDefOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        if (op.anchor.sweep != null) {
            return Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value, "particle_def '${op.def}': sweep is a vanilla particle rider (R14); a definition is emitted once per call"))
        }
        val placed = when (val r = ParticlePlacement.place("particle_def '${op.def}'", lc, op.anchor, op.shift)) {
            is Result.Success -> r.value
            is Result.Failure -> return r
        }
        return Result.Success(Lowered(rows = listOf(Row(tick = 0, instance = lc.self, action = Action.ParticleDef(op.def, placed.position), src = lc.src))))
    }
}
