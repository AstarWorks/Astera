package com.astarworks.astera.core.domain.hud.status

import com.astarworks.astera.core.domain.hud.HudComponent

/**
 * Pure model of the ammo readout that sits on the HP row, right of the vanilla
 * food (mana) row so it never covers it (F2-2, ADR-0194): `[bullet] cur/max` in
 * the status atlas digits.
 * Replaces the boss-bar weapon-resource HUD of ADR-0177; the reload progress
 * is the item cooldown sweep, not part of this string.
 */
public object AmmoReadoutLayout {

    /** Right edge of the vanilla food row, relative to the screen centre. */
    public const val FOOD_RIGHT_PX: Int = 91

    /** Clear pixels between the food row and the ammo readout. */
    public const val FOOD_CLEARANCE_PX: Int = 4

    public fun render(current: Int, max: Int): HudComponent.FontText {
        val maxClamped = max.coerceAtLeast(0)
        val cur = current.coerceIn(0, maxClamped)
        val out = StringBuilder()
        out.append(StatusHudGlyphs.AMMO)
        "$cur/$maxClamped".forEach { out.append(if (it == '/') StatusHudGlyphs.SLASH else StatusHudGlyphs.digit(it - '0')) }
        val text = out.toString()
        return HudComponent.FontText(text, StatusHudGlyphs.advance(text))
    }

    /**
     * The HP block followed by space up to [FOOD_CLEARANCE_PX] right of the food row, then the ammo
     * readout; a single [HudComponent.FontText] (the block starts at [HpBarLayout.HEARTS_LEFT_PX]).
     */
    public fun appendTo(hp: HudComponent.FontText, ammo: HudComponent.FontText): HudComponent.FontText {
        val gap = (FOOD_RIGHT_PX + FOOD_CLEARANCE_PX - (HpBarLayout.HEARTS_LEFT_PX + hp.widthPx)).coerceAtLeast(0)
        return HudComponent.FontText(
            text = hp.text + NegativeSpace.spacer(gap) + ammo.text,
            widthPx = hp.widthPx + gap + ammo.widthPx,
        )
    }
}
