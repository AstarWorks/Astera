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
import com.astarworks.astera.core.domain.effect.ScreenOverlay
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Val

/**
 * Decoded `screen_flash` op (ADR-0303 10.1b, founder Q7 = A 2026-10-29; fields of v1 `screen_flash`,
 * `0c9e986ef~1:core/feature-gameplay/.../config/skill/MechanicYaml.kt:1065-1098`): a full-screen cut-in [overlay] on the target's screen,
 * tinted [color], faded in / held / out for the given ticks (the client times it, so it needs no span).
 */
public class ScreenFlashOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    val target: TargetRef,
    val overlay: String,
    val color: String?,
    val fadeIn: Value<Double>,
    val stay: Value<Double>,
    val fadeOut: Value<Double>,
) : OpNode

/** `#rrggbb` check shared by the screen ops (v1 `HEX_COLOR`). */
internal fun screenHexRgb(raw: String, op: String, path: String, ctx: GrammarContext): Int {
    val ok = raw.length == 7 && raw.startsWith("#") && raw.substring(1).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    if (!ok) ctx.fail(path, "$op: color '$raw' is not #RRGGBB")
    return raw.substring(1).toInt(16)
}

public object ScreenFlashOpSpec : OpSpec<ScreenFlashOp> {
    private val targetField = Field.optional("target", GameplayOps.target, TargetRef.Caster, "victim, caster (default) or an area: whose screen")
    private val overlayField = Field.required("overlay", Types.enumOf(*ScreenOverlay.entries.map { it.yamlName }.toTypedArray()), "the cut-in glyph")
    private val colorField = Field.optional("color", Types.nullable(Types.id), null, "#RRGGBB tint; absent = the overlay's default colour")
    private val fadeInField = Field.optional("fade_in", Types.number, Value.Literal(0.0), "ticks to fade in")
    private val stayField = Field.optional("stay", Types.number, Value.Literal(1.0), "ticks held at full opacity")
    private val fadeOutField = Field.optional("fade_out", Types.number, Value.Literal(2.0), "ticks to fade out")

    override val name: String = "screen_flash"
    override val section: OpSection = OpSection.PRESENTATION
    override val timing: TimingKind? = TimingKind.POINT
    override val fields: Fields = Fields.of("screen_flash", targetField, overlayField, colorField, fadeInField, stayField, fadeOutField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<ScreenFlashOp, SkillDecodeError> {
        values[colorField]?.let { screenHexRgb(it, "screen_flash", "screen_flash(${id.value}).color", ctx) }
        return Result.Success(
            ScreenFlashOp(id, src, whenExpr, timing, values[targetField], values[overlayField], values[colorField], values[fadeInField], values[stayField], values[fadeOutField]),
        )
    }

    override fun lower(op: ScreenFlashOp, lc: LowerContext): Result<Lowered, SkillDecodeError> =
        GameplayOps.targetCell(op.target, lc, lc.src.value).map { target ->
            // the colour was validated at decode; an absent one is absent from the payload (the overlay's default tint)
            val payload = linkedMapOf<String, Val<Any>>(
                "target" to target,
                "overlay" to Val.Known(op.overlay),
                "fade_in" to CasterMotion.num(op.fadeIn),
                "stay" to CasterMotion.num(op.stay),
                "fade_out" to CasterMotion.num(op.fadeOut),
            )
            op.color?.let { payload["color"] = Val.Known(it.substring(1).toInt(16).toDouble()) }
            GameplayOps.effect(lc, "screen_flash", payload)
        }
}
