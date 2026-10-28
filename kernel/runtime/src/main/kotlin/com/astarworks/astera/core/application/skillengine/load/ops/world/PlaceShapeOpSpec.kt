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
import com.astarworks.astera.core.domain.skillengine.world.BuildShape
import com.astarworks.astera.core.domain.skillengine.world.PlaceShapeSpec

/** Decoded `place_shape` (ADR-0303 10.1c): place a build's cells nearest-first, one [PlaceShapeSpec.consume] item per placed cell. */
public class PlaceShapeOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val anchor: String,
    public val spec: PlaceShapeSpec,
) : OpNode

public object PlaceShapeOpSpec : OpSpec<PlaceShapeOp> {
    private val ANCHOR = WorldOps.anchorField
    private val SHAPE: Field<BuildShape> = Field.required("shape", WorldOps.buildShape, "wall, bridge, pillar or platform")
    private val BLOCK: Field<String> = Field.required("block", Types.id, "block id to place")
    private val CONSUME: Field<String?> = Field.required("consume", WorldOps.consume, "item id taken per placed cell, or none")
    private val RIPPLE: Field<Int> = Field.required("ripple", WorldOps.ripple, "ticks the cells are placed over, nearest first; 0 = all in the same tick")
    private val REVERT: Field<Int> = Field.required("revert", WorldOps.revert, "ticks until a placed plain block is removed, 1..6000")
    private val OUTSIDE = WorldOps.outsideArena

    override val name: String = "place_shape"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT
    override val fields: Fields = Fields.of("place_shape", ANCHOR, SHAPE, BLOCK, CONSUME, RIPPLE, REVERT, OUTSIDE)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<PlaceShapeOp, SkillDecodeError> =
        Result.Success(
            PlaceShapeOp(
                id, src, whenExpr, timing, values[ANCHOR],
                PlaceShapeSpec(values[SHAPE], values[BLOCK], values[CONSUME], values[RIPPLE], values[REVERT], values[OUTSIDE]),
            ),
        )

    override fun lower(op: PlaceShapeOp, lc: LowerContext): Result<Lowered, SkillDecodeError> =
        WorldOps.lower(OpId(name), op.anchor, mapOf<String, Val<Any>>("spec" to Val.Known(op.spec)), lc)
}
