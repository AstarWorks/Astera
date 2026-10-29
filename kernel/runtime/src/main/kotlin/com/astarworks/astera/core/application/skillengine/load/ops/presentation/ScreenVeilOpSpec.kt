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
import com.astarworks.astera.core.domain.effect.ScreenVeilMath
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Val

/**
 * Decoded `screen_veil` op (ADR-0303 10.1b, founder Q7 = A 2026-10-29; fields of `screen_veil`,
 * `0c9e986ef~1:core/feature-gameplay/.../config/skill/MechanicYaml.kt:1131-1163`): a camera-anchored sprite quad on the target's screen,
 * fading out over [ticks]. The op's instance owns the quad, so `ticks` lowers to an `End` row, and a state exit or cast cancel removes it.
 */
public class ScreenVeilOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    val target: TargetRef,
    val sprite: String,
    val distance: Value<Double>,
    val scale: Value<Double>,
    val ticks: Value<Double>,
    val color: String?,
    val maxScreenFraction: Value<Double>,
) : OpNode

public object ScreenVeilOpSpec : OpSpec<ScreenVeilOp> {
    private val targetField = Field.optional("target", GameplayOps.target, TargetRef.Caster, "victim, caster (default) or an area: whose screen")
    private val spriteField = Field.required("sprite", Types.id, "sprite id of the quad")
    private val distanceField = Field.optional("distance", Types.number, Value.Literal(0.6), "blocks in front of the eye, ${ScreenVeilMath.MIN_DISTANCE}..${ScreenVeilMath.MAX_DISTANCE}")
    private val scaleField = Field.optional("scale", Types.number, Value.Literal(1.0), "multiple of the 8 px sprite cell side")
    private val ticksField = Field.required("ticks", Types.number, "ticks the veil lives (it fades to nothing over them)")
    private val colorField = Field.optional("color", Types.nullable(Types.id), null, "#RRGGBB tint; absent = the sprite's own colours")
    private val maxFractionField = Field.optional("max_screen_fraction", Types.number, Value.Literal(0.15), "largest share of the screen it may cover, 0..${ScreenVeilMath.HARD_MAX_FRACTION}")

    override val name: String = "screen_veil"
    override val section: OpSection = OpSection.PRESENTATION
    override val timing: TimingKind? = TimingKind.POINT
    override val fields: Fields = Fields.of("screen_veil", targetField, spriteField, distanceField, scaleField, ticksField, colorField, maxFractionField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<ScreenVeilOp, SkillDecodeError> {
        val p = "screen_veil(${id.value})"
        values[colorField]?.let { screenHexRgb(it, "screen_veil", "$p.color", ctx) }
        // a distance / fraction outside its range is refused, never coerced; the table refuses it (a literal is known now, `${param}` / `=` are checked by the player)
        (values[distanceField] as? Value.Literal)?.value?.let {
            if (it < ScreenVeilMath.MIN_DISTANCE || it > ScreenVeilMath.MAX_DISTANCE) ctx.fail("$p.distance", "$it is outside ${ScreenVeilMath.MIN_DISTANCE}..${ScreenVeilMath.MAX_DISTANCE}")
        }
        (values[maxFractionField] as? Value.Literal)?.value?.let {
            if (it <= 0.0 || it > ScreenVeilMath.HARD_MAX_FRACTION) ctx.fail("$p.max_screen_fraction", "$it is outside (0, ${ScreenVeilMath.HARD_MAX_FRACTION}]")
        }
        return Result.Success(
            ScreenVeilOp(id, src, whenExpr, timing, values[targetField], values[spriteField], values[distanceField], values[scaleField], values[ticksField], values[colorField], values[maxFractionField]),
        )
    }

    override fun lower(op: ScreenVeilOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        return GameplayOps.targetCell(op.target, lc, path).flatMap { target ->
            lc.requireStaticTiming(path, "ticks", op.ticks).flatMap { ticks ->
                if (ticks < 1) {
                    Result.Failure(SkillDecodeError.Invalid(lc.skillId, path, "screen_veil ticks must be at least 1, got $ticks"))
                } else {
                    val payload = linkedMapOf<String, Val<Any>>(
                        "target" to target,
                        "sprite" to Val.Known(op.sprite),
                        "distance" to CasterMotion.num(op.distance),
                        "scale" to CasterMotion.num(op.scale),
                        "ticks" to Val.Known(ticks.toDouble()),
                        "max_screen_fraction" to CasterMotion.num(op.maxScreenFraction),
                    )
                    op.color?.let { payload["color"] = Val.Known(it) }
                    val rows = listOf(
                        Row(0, lc.self, Action.Effect(OpId("screen_veil"), payload), lc.src),
                        Row(ticks, lc.self, Action.End(lc.self), lc.src),
                    )
                    Result.Success(Lowered(rows = rows, subTables = emptyList(), instances = listOf(lc.self)))
                }
            }
        }
    }
}
