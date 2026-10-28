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
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.GameplayOps
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Val

/** Decoded `stop_sound` op (R26): stops the named sound event for the target player (vanilla `stopSound`). */
public class StopSoundOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    val target: TargetRef,
    val sound: String,
) : OpNode

public object StopSoundOpSpec : OpSpec<StopSoundOp> {
    private val targetField = Field.optional("target", GameplayOps.target, TargetRef.Caster, "victim, caster (default) or an area")
    private val soundField = Field.required("sound", Types.id, "sound event id to stop, e.g. entity.player.attack.sweep")

    override val name: String = "stop_sound"
    override val section: OpSection = OpSection.PRESENTATION
    override val timing: TimingKind? = TimingKind.POINT
    override val fields: Fields = Fields.of("stop_sound", targetField, soundField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<StopSoundOp, SkillDecodeError> =
        Result.Success(StopSoundOp(id, src, whenExpr, timing, values[targetField], values[soundField]))

    override fun lower(op: StopSoundOp, lc: LowerContext): Result<Lowered, SkillDecodeError> =
        GameplayOps.targetCell(op.target, lc, lc.src.value).map { target ->
            GameplayOps.effect(lc, "stop_sound", mapOf<String, Val<Any>>("target" to target, "sound" to Val.Known(op.sound)))
        }
}
