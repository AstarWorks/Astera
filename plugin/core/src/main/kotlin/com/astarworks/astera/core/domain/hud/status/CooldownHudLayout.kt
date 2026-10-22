package com.astarworks.astera.core.domain.hud.status

import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.skill.SkillTrigger
import kotlin.math.ceil

/**
 * Pure layout of the per-binding cooldown row (ADR-0195): one entry per bound
 * input of the held item, an icon plus the whole seconds left, or just the lit
 * icon when the ability is ready.
 */
public object CooldownHudLayout {

    /** One bound input of the held item and how long its skill is still on cooldown. */
    public data class Entry(val trigger: SkillTrigger, val remainingTicks: Long) {
        val ready: Boolean get() = remainingTicks <= 0L
    }

    public const val MAX_SECONDS: Int = 99
    public const val ENTRY_GAP_PX: Int = 3
    private const val TICKS_PER_SECOND: Double = 20.0

    /** Whole seconds shown for [remainingTicks]: rounded up, 1..[MAX_SECONDS]. */
    public fun seconds(remainingTicks: Long): Int =
        ceil(remainingTicks / TICKS_PER_SECOND).toInt().coerceIn(1, MAX_SECONDS)

    /** Glyph string of the row (no positioning). Entries whose input has no icon are skipped. */
    public fun glyphs(entries: List<Entry>): String {
        val out = StringBuilder()
        for (entry in ordered(entries)) {
            if (out.isNotEmpty()) out.append(NegativeSpace.spacer(ENTRY_GAP_PX))
            if (entry.ready) {
                out.append(CooldownHudGlyphs.ready(entry.trigger))
            } else {
                out.append(CooldownHudGlyphs.cooling(entry.trigger))
                seconds(entry.remainingTicks).toString().forEach { out.append(CooldownHudGlyphs.digit(it - '0')) }
            }
        }
        return out.toString()
    }

    /**
     * [block] with the cooldown row drawn over its top-left, one row higher. The
     * pen returns to the block's start before the row and is padded back to the
     * block's width afterwards, so [HudComponent.FontText.widthPx] (and with it
     * the anchoring, [StatusLineAnchor]) does not change.
     */
    public fun overlay(block: HudComponent.FontText, entries: List<Entry>): HudComponent.FontText {
        val row = glyphs(entries)
        if (row.isEmpty()) return block
        val rowWidth = CooldownHudGlyphs.advance(row)
        val text = block.text + NegativeSpace.spacer(-block.widthPx) + row + NegativeSpace.spacer(block.widthPx - rowWidth)
        return HudComponent.FontText(text, block.widthPx)
    }

    /** Text rendering for clients without the pack: `LC 3s`, `RC ready`, ... */
    public fun textParts(entries: List<Entry>): List<HudComponent.Text> = ordered(entries).map { entry ->
        val name = nameOf(entry.trigger)
        if (entry.ready) {
            HudComponent.Text(MessageKey("astera.hud.cooldown.$name.ready"), HudColor.GREEN)
        } else {
            HudComponent.Text(
                messageKey = MessageKey("astera.hud.cooldown.$name.waiting"),
                color = HudColor.GRAY,
                placeholders = mapOf("seconds" to seconds(entry.remainingTicks).toString()),
            )
        }
    }

    private fun ordered(entries: List<Entry>): List<Entry> =
        entries.filter { CooldownHudGlyphs.supports(it.trigger) }.sortedBy { CooldownHudGlyphs.indexOf(it.trigger) }

    private fun nameOf(trigger: SkillTrigger): String = when (trigger) {
        SkillTrigger.LEFT_CLICK -> "lc"
        SkillTrigger.RIGHT_CLICK -> "rc"
        SkillTrigger.SNEAK_LEFT -> "sneak-lc"
        SkillTrigger.SNEAK_RIGHT -> "sneak-rc"
        SkillTrigger.DROP -> "q"
        else -> error("no cooldown icon for $trigger")
    }
}
