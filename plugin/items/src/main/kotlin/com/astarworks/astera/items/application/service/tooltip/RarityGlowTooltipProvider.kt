package com.astarworks.astera.items.application.service.tooltip

import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.guidepth.ITooltipExtensionProvider
import com.astarworks.astera.core.domain.guidepth.TooltipLine
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Wave M 5polish-quickwin (ADR-0130 §D, sample #1) — adds a rarity badge
 * line to every item's tooltip.
 *
 * # Registration
 *
 * Phase C wiring registers this with
 * [com.astarworks.astera.core.application.port.outbound.guidepth.ITooltipRegistry]
 * under [com.astarworks.astera.core.domain.guidepth.TooltipScope.AllItems] —
 * one global line per item. The companion [STAR_PREFIX_BY_RARITY] map drives
 * the visual: a per-tier glyph plus the rarity name (resolved via
 * [com.astarworks.astera.core.domain.i18n.MessageKey] so each locale renders
 * its own translation).
 *
 * # Per-rarity colour
 *
 * [HudColor] reuse from Wave 5j gives each rarity its own tint without this
 * provider importing any Bukkit type. Wave 6 tooltip adapter maps `HudColor`
 * to Adventure `NamedTextColor`.
 *
 * # Side-effect-free
 *
 * Per the [ITooltipExtensionProvider] contract — `extend` must be pure
 * because the render path may call it on every cursor tick.
 */
public class RarityGlowTooltipProvider(
    private val items: IItemRegistry,
) : ITooltipExtensionProvider {

    override fun extend(item: ItemId, viewer: PlayerId): List<TooltipLine> {
        val spec = items.find(item) ?: return emptyList()
        val rarity = spec.rarity
        val key = MSG_KEY_BY_RARITY[rarity] ?: return emptyList()
        val color = COLOR_BY_RARITY[rarity] ?: HudColor.WHITE
        return listOf(TooltipLine.Text(messageKey = key, color = color))
    }

    public companion object {
        public val MSG_KEY_BY_RARITY: Map<Rarity, MessageKey> = mapOf(
            Rarity.COMMON to MessageKey("astera.tooltip.rarity.common"),
            Rarity.UNCOMMON to MessageKey("astera.tooltip.rarity.uncommon"),
            Rarity.RARE to MessageKey("astera.tooltip.rarity.rare"),
            Rarity.EPIC to MessageKey("astera.tooltip.rarity.epic"),
            Rarity.LEGENDARY to MessageKey("astera.tooltip.rarity.legendary"),
        )

        public val COLOR_BY_RARITY: Map<Rarity, HudColor> = mapOf(
            Rarity.COMMON to HudColor.WHITE,
            Rarity.UNCOMMON to HudColor.GREEN,
            Rarity.RARE to HudColor.AQUA,
            Rarity.EPIC to HudColor.LIGHT_PURPLE,
            Rarity.LEGENDARY to HudColor.GOLD,
        )

        /**
         * Star prefix unused at render time (the i18n string carries any glyph
         * the texture pack wants) — kept as a balance reference so designers
         * see the canonical 1-5 star ladder when tuning the rarity table.
         */
        @Suppress("unused")
        public val STAR_PREFIX_BY_RARITY: Map<Rarity, String> = mapOf(
            Rarity.COMMON to "⭐",
            Rarity.UNCOMMON to "⭐⭐",
            Rarity.RARE to "⭐⭐⭐",
            Rarity.EPIC to "⭐⭐⭐⭐",
            Rarity.LEGENDARY to "⭐⭐⭐⭐⭐",
        )
    }
}
