package com.astarworks.astera.core.application.port.outbound.guidepth

import com.astarworks.astera.core.domain.guidepth.TooltipLine
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: per-viewer dynamic tooltip line injection
 * (Wave 5gui-depth, ADR-0114).
 *
 * Implemented by feature plugins (Wave 5refine refinement tier display, Wave
 * 5flex glow-rarity badge, Wave 5bet betting-status overlay, Wave 5pve-survival
 * gathering-skill cooldown). The implementation is registered with an
 * [ITooltipRegistry] under a [com.astarworks.astera.core.domain.guidepth.TooltipScope]
 * predicate; the registry calls [extend] for every viewer / item pair whose
 * scope matches.
 *
 * Per-viewer by design — `viewer` may matter (e.g., team-colour highlight,
 * cooldown remaining for this player). Providers that don't care about the
 * viewer simply ignore the parameter.
 *
 * Vendor-neutral by construction — no Bukkit `ItemStack` / `Component` in
 * the surface; just the domain [TooltipLine] vocabulary.
 */
public fun interface ITooltipExtensionProvider {

    /**
     * Compute extra tooltip lines for [item] as seen by [viewer]. Return an
     * empty list if the provider has nothing to add for this pair (the
     * registry aggregates results from every matching provider).
     *
     * The provider must be side-effect-free — `lookup` may be called from
     * the render path on every tick the cursor sits over the item.
     */
    public fun extend(item: ItemId, viewer: PlayerId): List<TooltipLine>
}
