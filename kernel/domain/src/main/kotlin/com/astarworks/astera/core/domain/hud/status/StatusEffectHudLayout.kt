package com.astarworks.astera.core.domain.hud.status

import com.astarworks.astera.core.domain.hud.HudComponent

/** A separate row above cooldowns, with no change to the HP/ammo block's pen advance. */
public object StatusEffectHudLayout {
    public data class Entry(val id: String, val remainingTicks: Int, val stacks: Int)

    public fun glyphs(entries: List<Entry>): String = entries
        .filter { it.remainingTicks > 0 && it.id in StatusEffectHudGlyphs.IDS }
        .sortedBy { it.id }
        .joinToString(NegativeSpace.spacer(3)) { entry ->
            val seconds = CooldownHudLayout.seconds(entry.remainingTicks.toLong()).toString()
            val badge = if (entry.stacks > 1) entry.stacks.coerceAtMost(99).toString() else ""
            val width = maxOf(seconds.length, badge.length) * StatusEffectHudGlyphs.DIGIT_ADVANCE
            StatusEffectHudGlyphs.icon(entry.id).toString() +
                badge.map { StatusEffectHudGlyphs.stack(it - '0') }.joinToString("") +
                NegativeSpace.spacer(-badge.length * StatusEffectHudGlyphs.DIGIT_ADVANCE) +
                seconds.map { StatusEffectHudGlyphs.digit(it - '0') }.joinToString("") +
                NegativeSpace.spacer(width - seconds.length * StatusEffectHudGlyphs.DIGIT_ADVANCE)
        }

    public fun overlay(block: HudComponent.FontText, entries: List<Entry>): HudComponent.FontText {
        val row = glyphs(entries)
        if (row.isEmpty()) return block
        return HudComponent.FontText(
            block.text + NegativeSpace.spacer(-block.widthPx) + row +
                NegativeSpace.spacer(block.widthPx - StatusEffectHudGlyphs.advance(row)),
            block.widthPx,
        )
    }
}
