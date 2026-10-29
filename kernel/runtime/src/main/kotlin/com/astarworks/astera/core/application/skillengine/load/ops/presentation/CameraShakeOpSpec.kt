package com.astarworks.astera.core.application.skillengine.load.ops.presentation

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.CasterMotion
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.GameplayOps
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Val

/**
 * Decoded `camera_shake` op (R26): shakes the target player's camera for the span's ticks. `direction: from_hit` leans the
 * shake away from the hit that raised the event (v2 `cameraKick` direction), `none` shakes without a direction.
 */
public class CameraShakeOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    val target: TargetRef,
    val intensity: Value<Double>,
    val direction: String,
) : OpNode

public object CameraShakeOpSpec : OpSpec<CameraShakeOp> {
    private val targetField = Field.optional("target", GameplayOps.target, TargetRef.Caster, "victim, caster (default) or an area")
    private val intensityField = Field.required("intensity", Types.number, "shake strength")
    private val directionField = Field.optional("direction", Types.enumOf("from_hit", "none"), "none", "from_hit leans away from the hit")

    override val name: String = "camera_shake"
    override val section: OpSection = OpSection.PRESENTATION
    override val timing: TimingKind? = TimingKind.EXTENDED
    override val fields: Fields = Fields.of("camera_shake", targetField, intensityField, directionField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<CameraShakeOp, SkillDecodeError> =
        Result.Success(CameraShakeOp(id, src, whenExpr, timing, values[targetField], values[intensityField], values[directionField]))

    override fun lower(op: CameraShakeOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        if (op.direction == "from_hit" && !lc.hitScope) {
            return Result.Failure(SkillDecodeError.Invalid(lc.skillId, path, "camera_shake direction: from_hit is only valid inside an on.hit handler (no hit victim is bound here); use direction: none"))
        }
        return GameplayOps.targetCell(op.target, lc, path).flatMap { target ->
            GameplayOps.duration(op.timing, lc, path, "for").map { ticks ->
                GameplayOps.effect(
                    lc, "camera_shake",
                    mapOf<String, Val<Any>>(
                        "target" to target,
                        "intensity" to CasterMotion.num(op.intensity),
                        "ticks" to Val.Known(ticks.toDouble()),
                        "direction" to Val.Known(op.direction),
                    ),
                )
            }
        }
    }
}
