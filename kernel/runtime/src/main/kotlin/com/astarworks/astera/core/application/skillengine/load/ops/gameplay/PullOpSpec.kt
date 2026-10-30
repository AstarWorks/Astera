package com.astarworks.astera.core.application.skillengine.load.ops.gameplay

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
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Val

/** Decoded `pull` op: launch the target along [direction] (horizontal part) so that it travels [distance] blocks and rests, with [lift] upward. */
public class PullOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    val target: TargetRef,
    val direction: Value<Vec3>,
    val distance: Value<Double>,
    val lift: Value<Double>,
) : OpNode

public object PullOpSpec : OpSpec<PullOp> {
    override val name: String = "pull"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT

    private val targetField = Field.required("target", GameplayOps.target, "victim, caster or an area")
    private val directionField = Field.required("direction", Types.worldVec3, "world vec3 toward the pull point; only its horizontal part is used")
    private val distanceField = Field.required("distance", Types.number, "horizontal blocks the target should travel before it rests (drag and gravity solved); <= 0 does nothing")
    private val liftField = Field.optional("lift", Types.number, Value.Literal(0.3), "upward launch velocity, blocks per tick")

    override val fields: Fields = Fields.of("pull", targetField, directionField, distanceField, liftField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<PullOp, SkillDecodeError> =
        Result.Success(PullOp(id, src, whenExpr, timing, values[targetField], values[directionField], values[distanceField], values[liftField]))

    override fun lower(op: PullOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        return GameplayOps.targetCell(op.target, lc, path).flatMap { target ->
            Result.Success(
                GameplayOps.effect(
                    lc,
                    "pull",
                    mapOf<String, Val<Any>>(
                        "target" to target,
                        "direction" to GameplayOps.liveVec(op.direction),
                        "distance" to GameplayOps.live(op.distance),
                        "lift" to GameplayOps.live(op.lift),
                    ),
                ),
            )
        }
    }
}
