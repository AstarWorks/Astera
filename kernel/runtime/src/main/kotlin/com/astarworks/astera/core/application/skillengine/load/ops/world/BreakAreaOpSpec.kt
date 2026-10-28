package com.astarworks.astera.core.application.skillengine.load.ops.world

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
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
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.world.AreaShape
import com.astarworks.astera.core.domain.skillengine.world.BreakAreaSpec

/** Decoded `break_area` (ADR-0303 10.1c): strike a shape's blocks nearest-first, then restore them after [BreakAreaSpec.revertTicks]. */
public class BreakAreaOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val anchor: String,
    public val spec: BreakAreaSpec,
) : OpNode

public object BreakAreaOpSpec : OpSpec<BreakAreaOp> {
    private val ANCHOR = WorldOps.anchorField
    private val SHAPE: Field<AreaShape> = Field.required("shape", WorldOps.areaShape, "sphere, line or box")
    private val TOOL: Field<String> = Field.required("tool", Types.enumOf("axe", "pickaxe", "shovel", "none"), "tool class the strike counts as")
    private val BLOCK_DAMAGE: Field<Int> = Field.required("block_damage", WorldOps.blockDamage, "block HP removed per strike, 0..2000")
    private val RIPPLE: Field<Int> = Field.required("ripple", WorldOps.ripple, "ticks the cells strike over, nearest first; 0 = all in the same tick")
    private val REVERT: Field<Int> = Field.required("revert", WorldOps.revert, "ticks until a broken plain block is restored, 1..6000")
    private val OUTSIDE = WorldOps.outsideArena

    override val name: String = "break_area"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT
    override val fields: Fields = Fields.of("break_area", ANCHOR, SHAPE, TOOL, BLOCK_DAMAGE, RIPPLE, REVERT, OUTSIDE)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<BreakAreaOp, SkillDecodeError> =
        Result.Success(
            BreakAreaOp(
                id, src, whenExpr, timing, values[ANCHOR],
                BreakAreaSpec(values[SHAPE], values[TOOL], values[BLOCK_DAMAGE], values[RIPPLE], values[REVERT], values[OUTSIDE]),
            ),
        )

    override fun lower(op: BreakAreaOp, lc: LowerContext): Result<Lowered, SkillDecodeError> =
        WorldOps.lower(OpId(name), op.anchor, mapOf<String, Val<Any>>("spec" to Val.Known(op.spec)), lc)
}
