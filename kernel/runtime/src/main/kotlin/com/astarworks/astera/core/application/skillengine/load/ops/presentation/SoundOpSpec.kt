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
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Num
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.Val
import org.joml.Vector3d

/**
 * Decoded `sound` op (ADR-0303 2.0b / 2.2 / 3.4). Every field maps 1:1 to the vanilla `playSound` arguments: volume and pitch
 * default to the vanilla identity 1.0, category to MASTER. `anchor` is the source point and is required.
 */
public class SoundOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val sound: String,
    public val anchor: String,
    public val volume: Value<Double>,
    public val pitch: Value<Double>,
    public val category: String,
) : OpNode

public object SoundOpSpec : OpSpec<SoundOp> {
    /** Vanilla SoundCategory names, lowercase (the Paper gateway maps them back to the enum). */
    private val CATEGORIES = listOf("master", "music", "records", "weather", "blocks", "hostile", "neutral", "players", "ambient", "voice")

    private val SOUND: Field<String> = Field.required("sound", Types.id, "sound event id, e.g. entity.player.attack.sweep")
    private val ANCHOR: Field<String> = Field.required("anchor", Types.id, "source point: an anchor name declared in the skill")
    private val VOLUME: Field<Value<Double>> = Field.optional("volume", Types.number, Value.Literal(1.0), "vanilla volume, 1.0 by default")
    private val PITCH: Field<Value<Double>> = Field.optional("pitch", Types.number, Value.Literal(1.0), "vanilla pitch, 1.0 by default")
    private val CATEGORY: Field<String> = Field.optional("category", Types.enumOf(*CATEGORIES.toTypedArray()), "master", "vanilla SoundCategory")

    override val name: String = "sound"
    override val section: OpSection = OpSection.PRESENTATION
    override val timing: TimingKind? = TimingKind.POINT
    override val fields: Fields = Fields.of("sound", SOUND, ANCHOR, VOLUME, PITCH, CATEGORY)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<SoundOp, SkillDecodeError> =
        Grammar.guard {
            val path = "sound[${id.value}]"
            SoundOp(
                id = id,
                src = src,
                whenExpr = whenExpr,
                timing = timing,
                sound = values[SOUND],
                anchor = values[ANCHOR],
                volume = ctx.checked(values[VOLUME], ExprType.NUM, "$path.volume"),
                pitch = ctx.checked(values[PITCH], ExprType.NUM, "$path.pitch"),
                category = values[CATEGORY],
            )
        }

    /**
     * One row at this occurrence's own tick. The position is the resolved anchor instance (a live value, read at play time).
     * Literals stay [Val.Known]; a `${param}` and a `= CEL` stay [Val.Live] until the player resolves them (see W1-24 / W1-19).
     */
    override fun lower(op: SoundOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val anchor = lc.anchors.resolve(op.anchor)
            ?: return Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value, "anchor '${op.anchor}' is not declared in this scope"))
        val action = Action.Sound(
            sound = op.sound,
            volume = number(op.volume),
            pitch = number(op.pitch),
            position = Val.Live<Vector3d>(anchor.instance.text, null),
            category = op.category,
        )
        return Result.Success(Lowered(rows = listOf(Row(lc.clockOffset, lc.self, action, lc.src))))
    }

    private fun number(v: Value<Double>): Num = when (v) {
        is Value.Literal -> Val.Known(v.value)
        is Value.ParamRef -> Val.Live<Double>("\${${v.name}}", null)
        is Value.Expr -> Val.Live<Double>("= ${v.source}", null)
    }
}
