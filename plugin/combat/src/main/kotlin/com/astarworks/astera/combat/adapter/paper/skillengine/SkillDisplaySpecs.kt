package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.application.skillengine.load.MeshModel
import com.astarworks.astera.core.application.skillengine.runtime.port.MeshLook
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
 * gateway and the game-sim recorder build the very same spec.
 */
public object SkillDisplaySpecs {
    /** Lifetime handed to a skill display; the cast removes it explicitly. */
    public const val LIFETIME: Long = 1_000_000L

    private const val MIN_TEXT_OPACITY: Int = 4

    public fun opacity(alpha: Double): Int = (alpha.coerceIn(0.0, 1.0) * 255.0).roundToInt().coerceAtLeast(MIN_TEXT_OPACITY)

    /** `#rrggbb` or `#aarrggbb` (alpha FIRST, the one order every skill colour key uses, ADR-0303 R33) to ARGB; a malformed value is opaque white. */
    public fun argb(hex: String): Int {
        val h = hex.removePrefix("#")
        val v = h.toLongOrNull(16) ?: return 0xFFFFFFFF.toInt()
        return if (h.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    }

    /** The alpha (0..1) a colour string carries: `#aarrggbb` its first byte, `#rrggbb` 1. */
    public fun alphaOf(hex: String): Double = ((argb(hex) ushr 24) and 0xFF) / 255.0

    /** glow_color: `#rrggbb` or `#aarrggbb` (alpha first); null stays unglowing. */
    public fun glowArgb(hex: String?): Int? {
        val h = hex?.removePrefix("#") ?: return null
        val v = h.toLongOrNull(16) ?: return null
        return if (h.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    }

    /** The item a pack or vanilla mesh model draws; null for the caster's held item (needs the caster). */
    public fun meshKind(model: MeshModel, look: MeshLook = MeshLook()): DisplayKind = when (model) {
        is MeshModel.Pack -> DisplayKind.Item(ItemId("paper"), look.mode, look.itemModel ?: model.key)
        is MeshModel.Vanilla -> DisplayKind.Item(ItemId(model.material.replace('_', '-')), look.mode, look.itemModel ?: model.itemModel)
        MeshModel.HeldItem -> DisplayKind.AnchorMainHand // AnchorMainHand: the caster's real stack in THIRDPERSON_RIGHTHAND
    }

    public fun mesh(kind: DisplayKind, first: DisplayTransform, brightness: Int? = null, rigShading: Boolean = true, shaderEffect: String? = null): DisplaySpec =
        DisplaySpec(kind = kind, transform = first, lifetimeTicks = LIFETIME, billboard = DisplayBillboard.FIXED, brightness = brightness, weaponRigShading = rigShading && brightness == null && shaderEffect == null, shaderEffect = shaderEffect)

    public fun block(blockKey: String, first: DisplayTransform, brightness: Int?, rigShading: Boolean = false): DisplaySpec =
        DisplaySpec(kind = DisplayKind.Block(blockKey), transform = first, lifetimeTicks = LIFETIME, billboard = DisplayBillboard.FIXED, brightness = brightness, weaponRigShading = rigShading && brightness == null)

    /** A solid-colour quad (`text` null: one blank line on a background of [colorHex] at [alpha]) or a text glyph drawn over it. */
    public fun quad(colorHex: String, alpha: Double, text: String?, shadow: Boolean, seeThrough: Boolean, first: DisplayTransform, billboard: DisplayBillboard, brightness: Int?): DisplaySpec {
        val a = (alpha.coerceIn(0.0, 1.0) * 255.0).roundToInt()
        val rgb = argb(colorHex) and 0xFFFFFF
        val shown = text ?: " "
        // Solid vs glyph split: a solid quad paints its background, a glyph quad keeps no box and tints / fades the glyph itself.
        val kind = if (text == null) {
            DisplayKind.Text(
                lines = listOf(LocalizedText(mapOf(LocaleCode.EN to shown, LocaleCode.JA to shown))),
                backgroundArgb = (a shl 24) or rgb, shadow = shadow, seeThrough = seeThrough, textOpacity = 255,
            )
        } else {
            DisplayKind.Text(
                lines = listOf(LocalizedText(mapOf(LocaleCode.EN to shown, LocaleCode.JA to shown))),
                backgroundArgb = 0, shadow = shadow, seeThrough = seeThrough, textOpacity = a.coerceAtLeast(MIN_TEXT_OPACITY), textRgb = rgb,
            )
        }
        return DisplaySpec(
            kind = kind,
            transform = first, lifetimeTicks = LIFETIME, billboard = billboard, brightness = brightness,
        )
    }

    /** A MiniMessage [text] display with a box of [backgroundArgbHex] (`#aarrggbb`, `#00000000` = none); the text keeps its own colour and full opacity. */
    public fun text(text: String, backgroundArgbHex: String, shadow: Boolean, seeThrough: Boolean, first: DisplayTransform, billboard: DisplayBillboard, brightness: Int?): DisplaySpec {
        return DisplaySpec(
            kind = DisplayKind.Text(
                lines = listOf(LocalizedText(mapOf(LocaleCode.EN to text, LocaleCode.JA to text))),
                backgroundArgb = argb(backgroundArgbHex), shadow = shadow, seeThrough = seeThrough, textOpacity = 255,
            ),
            transform = first, lifetimeTicks = LIFETIME, billboard = billboard, brightness = brightness,
        )
    }

    /** One skin-rig bone: a skull display wearing the owner's skin (ADR-0303 skin-rig section). */
    public fun skinBone(kind: DisplayKind.SkinBone, first: DisplayTransform): DisplaySpec =
        DisplaySpec(kind = kind, transform = first, lifetimeTicks = LIFETIME, billboard = DisplayBillboard.FIXED)

    public fun plate(glyph: String, alpha: Double, seeThrough: Boolean, first: DisplayTransform, billboard: DisplayBillboard, brightness: Int? = 15): DisplaySpec =
        DisplaySpec(
            kind = DisplayKind.Text(
                lines = listOf(LocalizedText(mapOf(LocaleCode.EN to glyph, LocaleCode.JA to glyph))),
                backgroundArgb = 0, shadow = false, seeThrough = seeThrough, textOpacity = opacity(alpha),
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
