package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.adapter.api.item.IMcItemTemplate
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.service.item.ItemTooltip
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.item.ItemTemplate
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Convert an [ItemSpec] (the canonical materialized form, Wave _items-1 /
 * ADR-0073) into the adapter-boundary [IMcItemTemplate] flavor.
 *
 * The application layer works on [ItemSpec] (vendor-neutral, inline-i18n,
 * pure domain). The adapter layer materializes Paper / Folia / web stacks
 * from a thinner shape that already has its display name + lore resolved
 * to MiniMessage strings — the conversion lives here so the adapter side
 * does not need to know about [IMessageRenderer] or
 * `LocalizedText` / `LocalizedLore`.
 *
 * Conversion behaviour:
 *
 *  - [ItemSpec.template] determines the `IMcItemTemplate` flavor:
 *    [ItemTemplate.Vanilla] → [IMcItemTemplate.Vanilla];
 *    [ItemTemplate.External] → [IMcItemTemplate.External] (display name +
 *    lore are dropped — the external provider owns its own rendering).
 *  - [ItemSpec.displayName] resolves through
 *    [IMessageRenderer.renderText] for the supplied [viewer]. When the spec
 *    carries [LocalizedText.EMPTY], the adapter template's `displayName` is
 *    set to `null` (i.e. "leave the Material's default name").
 *  - [ItemSpec.lore] resolves through [IMessageRenderer.renderLocalizedLore].
 *    Empty map (i.e. [LocalizedLore.EMPTY]) yields an empty list so the
 *    adapter skips the lore section entirely.
 *  - [ItemSpec.pdcTags] are passed through verbatim. The canonical
 *    `astera:item-id` tag is *not* injected here — the adapter writes it
 *    unconditionally, so callers don't double-stamp.
 *
 * @param messages The renderer used to resolve inline i18n.
 * @param viewer The player whose locale should be used for rendering, or
 *   `null` to fall back to the renderer's default locale (matches the
 *   semantics of [IMessageRenderer.renderText]).
 */
public fun ItemSpec.toAdapterTemplate(
    messages: IMessageRenderer,
    viewer: PlayerId?,
): IMcItemTemplate = when (val tpl = this.template) {
    is ItemTemplate.Vanilla -> {
        val rendered = if (displayName == LocalizedText.EMPTY) {
            null
        } else {
            messages.renderText(viewer, displayName)
        }
        // One renderer for every surface (ADR-0211): rarity / level / stats are derived from the spec.
        val renderedLore = ItemTooltip(messages).lore(this, viewer)
        IMcItemTemplate.Vanilla(
            id = id,
            material = tpl.material,
            customModelData = tpl.customModelData,
            displayName = rendered,
            lore = renderedLore,
            pdcTags = pdcTags,
        )
    }

    is ItemTemplate.External -> IMcItemTemplate.External(
        id = id,
        key = tpl.externalKey,
    )
}
