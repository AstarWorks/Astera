package com.astarworks.astera.core.domain.gui

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Rich result returned by [com.astarworks.astera.core.application.port.outbound.IGuiBindingResolver.resolve].
 *
 * Wave 4.5 returned `ItemId?` — sufficient for `player.loadout.<slot>` style
 * bindings where the slot picks an icon. Wave 5f (RTM #21) needs numeric
 * bindings like `player.coins.star` → render an "indicator-coin" icon with
 * a display name override (`"500 STAR"`) and count override (500).
 * [com.astarworks.astera.core.adapter.paper.gui.PaperItemTemplateRenderer]
 * applies the overrides via MiniMessage.
 *
 * All overrides are nullable — `null` means "fall back to the static slot
 * definition from the GUI YAML" (i.e. the item template's `displayName` / `lore`
 * / the slot's `count`).
 */
public data class ResolvedReactiveSlot(
    public val itemId: ItemId,
    public val displayOverride: MessageKey? = null,
    public val loreOverride: List<MessageKey>? = null,
    /** Stack count (1..64). null → fall back to GUI YAML's count. */
    public val countOverride: Int? = null,
) {
    init {
        if (countOverride != null) {
            require(countOverride in 1..64) {
                "countOverride must be in [1, 64]: $countOverride"
            }
        }
    }
}
