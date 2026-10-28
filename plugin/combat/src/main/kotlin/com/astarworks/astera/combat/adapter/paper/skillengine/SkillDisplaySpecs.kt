package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.application.skillengine.load.MeshModel
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayItemMode
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.ItemId
import kotlin.math.roundToInt

/**
 * The vanilla display a skill mesh / plate / line spawns as (kind, text, billboard, brightness, shading). Pure, so the Paper
 * gateway and the vanilla-parity recorder (VanillaParityScenarioTest) build the very same spec.
 */
public object SkillDisplaySpecs {
    /** Lifetime handed to a skill display; the cast removes it explicitly. */
    public const val LIFETIME: Long = 1_000_000L

    private const val MIN_TEXT_OPACITY: Int = 4

    public fun opacity(alpha: Double): Int = (alpha.coerceIn(0.0, 1.0) * 255.0).roundToInt().coerceAtLeast(MIN_TEXT_OPACITY)

    /** `#rrggbb` or `#rrggbbaa` (alpha last) to ARGB; a malformed value is white. */
    public fun argb(hex: String): Int {
        val h = hex.removePrefix("#")
        val rgb = h.take(6).toIntOrNull(16) ?: return 0xFFFFFFFF.toInt()
        val a = if (h.length >= 8) h.substring(6, 8).toIntOrNull(16) ?: 255 else 255
        return (a shl 24) or rgb
    }

    /** The item a pack or vanilla mesh model draws; null for the caster's held item (needs the caster). */
    public fun meshKind(model: MeshModel): DisplayKind.Item? = when (model) {
        is MeshModel.Pack -> DisplayKind.Item(ItemId("paper"), DisplayItemMode.FIXED, model.key)
        is MeshModel.Vanilla -> DisplayKind.Item(ItemId(model.material.replace('_', '-')), DisplayItemMode.FIXED, null)
        MeshModel.HeldItem -> null
    }

    public fun mesh(kind: DisplayKind.Item, first: DisplayTransform, brightness: Int? = null): DisplaySpec =
        DisplaySpec(kind = kind, transform = first, lifetimeTicks = LIFETIME, billboard = DisplayBillboard.FIXED, brightness = brightness, weaponRigShading = brightness == null)

    public fun block(blockKey: String, first: DisplayTransform, brightness: Int?): DisplaySpec =
        DisplaySpec(kind = DisplayKind.Block(blockKey), transform = first, lifetimeTicks = LIFETIME, billboard = DisplayBillboard.FIXED, brightness = brightness)

    /** A solid-colour quad (`text` null: one blank line on a background of [colorHex] at [alpha]) or a text glyph drawn over it. */
    public fun quad(colorHex: String, alpha: Double, text: String?, shadow: Boolean, seeThrough: Boolean, first: DisplayTransform, billboard: DisplayBillboard, brightness: Int?): DisplaySpec {
        val a = (alpha.coerceIn(0.0, 1.0) * 255.0).roundToInt()
        val bg = (a shl 24) or (argb(colorHex) and 0xFFFFFF)
        val shown = text ?: " "
        return DisplaySpec(
            kind = DisplayKind.Text(
                lines = listOf(LocalizedText(mapOf(LocaleCode.EN to shown, LocaleCode.JA to shown))),
                backgroundArgb = bg, shadow = shadow, seeThrough = seeThrough, textOpacity = 255,
            ),
            transform = first, lifetimeTicks = LIFETIME, billboard = billboard, brightness = brightness,
        )
    }

    public fun plate(glyph: String, alpha: Double, first: DisplayTransform, billboard: DisplayBillboard, brightness: Int? = 15): DisplaySpec =
        DisplaySpec(
            kind = DisplayKind.Text(
                lines = listOf(LocalizedText(mapOf(LocaleCode.EN to glyph, LocaleCode.JA to glyph))),
                backgroundArgb = 0, shadow = false, seeThrough = false, textOpacity = opacity(alpha),
            ),
            transform = first, lifetimeTicks = LIFETIME, billboard = billboard, brightness = brightness,
        )

    public fun line(colorHex: String, first: DisplayTransform): DisplaySpec =
        DisplaySpec(
            kind = DisplayKind.Text(
                lines = listOf(LocalizedText(mapOf(LocaleCode.EN to " ", LocaleCode.JA to " "))),
                backgroundArgb = argb(colorHex), shadow = false, seeThrough = false, textOpacity = 255,
            ),
            transform = first, lifetimeTicks = LIFETIME, billboard = DisplayBillboard.FIXED, brightness = 15,
        )
}
