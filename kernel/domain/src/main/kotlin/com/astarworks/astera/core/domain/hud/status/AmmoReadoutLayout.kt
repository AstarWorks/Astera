package com.astarworks.astera.core.domain.hud.status

import com.astarworks.astera.core.domain.hud.HudComponent

/**
 * Pure model of the ammo readout that sits right of the HP bar in the status
 * line (F2-2, ADR-0194): `[bullet] cur/max` in the status atlas digits.
 * Replaces the boss-bar weapon-resource HUD of ADR-0177; the reload progress
 * is the item cooldown sweep, not part of this string.
 */
public object AmmoReadoutLayout {

    /** Pixels between the HP block and the ammo readout. */
    public const val GAP_PX: Int = 8

    public fun render(current: Int, max: Int): HudComponent.FontText {
        val maxClamped = max.coerceAtLeast(0)
        val cur = current.coerceIn(0, maxClamped)
        val out = StringBuilder()
        out.append(StatusHudGlyphs.AMMO)
        "$cur/$maxClamped".forEach { out.append(if (it == '/') StatusHudGlyphs.SLASH else StatusHudGlyphs.digit(it - '0')) }
        val text = out.toString()
        return HudComponent.FontText(text, StatusHudGlyphs.advance(text))
    }

    /** The HP block followed by [GAP_PX] of space and the ammo readout; a single [HudComponent.FontText]. */
    public fun appendTo(hp: HudComponent.FontText, ammo: HudComponent.FontText): HudComponent.FontText =
        HudComponent.FontText(
            text = hp.text + NegativeSpace.spacer(GAP_PX) + ammo.text,
            widthPx = hp.widthPx + GAP_PX + ammo.widthPx,
        )
}
