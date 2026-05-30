package com.astarworks.astera.items.application.service.tooltip

import com.astarworks.astera.core.application.port.outbound.guidepth.ITooltipExtensionProvider
import com.astarworks.astera.core.domain.guidepth.TooltipLine
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.items.application.port.outbound.IItemInstanceRepository

/**
 * Wave M 5polish-quickwin (ADR-0130 §D, sample #2) — adds a refinement-tier
 * summary line to refined items.
 *
 * # Registration
 *
 * Phase C wiring registers this with
 * [com.astarworks.astera.core.application.port.outbound.guidepth.ITooltipRegistry]
 * under [com.astarworks.astera.core.domain.guidepth.TooltipScope.ByRarity]
 * (Rare / Epic / Legendary — one registration per tier; the registry's
 * scope semantics call this provider for every matching item).
 *
 * # What the line shows
 *
 * Counts the viewer's [com.astarworks.astera.items.domain.refinement.ItemInstance]s
 * of [item] and reports the **highest refinement layer count** across them.
 * Phase 1 PoC heuristic; once
 * [com.astarworks.astera.items.domain.refinement.ItemInstanceId] travels in
 * the tooltip lookup signature (Wave 6+ adapter widening), this provider
 * can resolve the actually-hovered instance and skip the across-all-instances
 * collapse. Until then this single-best surface is the readable approximation.
 *
 * If the viewer owns no instance of [item] the provider returns an empty list
 * (no tooltip noise on items they haven't refined yet).
 *
 * # Side-effect-free
 *
 * Per [ITooltipExtensionProvider] contract — `extend` must be pure. The
 * repository's `findByOwner` is read-only and the highest-tier calculation
 * is a fold.
 */
public class RefinementTierTooltipProvider(
    private val instances: IItemInstanceRepository,
) : ITooltipExtensionProvider {

    override fun extend(item: ItemId, viewer: PlayerId): List<TooltipLine> {
        val owned = instances.findByOwner(viewer).filter { it.itemId == item }
        if (owned.isEmpty()) return emptyList()
        val highest = owned.maxOf { it.layers().size }
        if (highest <= 0) return emptyList()
        return listOf(
            TooltipLine.Text(
                messageKey = MSG_TIER,
                color = HudColor.LIGHT_PURPLE,
            ),
        )
    }

    public companion object {
        public val MSG_TIER: MessageKey = MessageKey("astera.tooltip.refinement.tier")
    }
}
