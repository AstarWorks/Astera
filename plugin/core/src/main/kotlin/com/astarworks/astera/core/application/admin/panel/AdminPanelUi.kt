package com.astarworks.astera.core.application.admin.panel

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.GuiText
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Intent keys of the `/astera-admin` panel (F1-D, ADR-0183). Each plugin owns
 * one `admin.<area>.` prefix on `IGuiIntentRouter` (ADR-0119 ownership rule).
 */
public object AdminIntents {
    public const val HUB_OPEN: String = "admin.hub.open"
    public const val CORE_PLAYERS_PREFIX: String = "admin.players."
    public const val CORE_SERVER_PREFIX: String = "admin.server."
    public const val CORE_SELF_PREFIX: String = "admin.self."
    public const val ITEMS_PREFIX: String = "admin.items."
    public const val MATCH_PREFIX: String = "admin.match."
    public const val VALIDATE_PREFIX: String = "admin.validate."
    public const val CURRENCY_PREFIX: String = "admin.currency."
    public const val COMBAT_PREFIX: String = "admin.combat."
}

/** Shared widgets and the open-or-update helper for panel pages. */
public object AdminPanelUi {

    public val BACK_ICON: ItemId = ItemId("gui-admin-back")
    public val CLOSE_ICON: ItemId = ItemId("gui-admin-close")
    public val PREV_ICON: ItemId = ItemId("gui-admin-prev")
    public val NEXT_ICON: ItemId = ItemId("gui-admin-next")
    public val PAGE_ICON: ItemId = ItemId("gui-admin-page")
    public val REFRESH_ICON: ItemId = ItemId("gui-admin-refresh")
    public val FILLER: ItemId = ItemId("gui-pane-dark")

    public val K_BACK: MessageKey = MessageKey("astera.admin.panel.nav.back")
    public val K_CLOSE: MessageKey = MessageKey("astera.admin.panel.nav.close")
    public val K_PREV: MessageKey = MessageKey("astera.admin.panel.nav.prev")
    public val K_NEXT: MessageKey = MessageKey("astera.admin.panel.nav.next")
    public val K_PAGE: MessageKey = MessageKey("astera.admin.panel.nav.page")
    public val K_REFRESH: MessageKey = MessageKey("astera.admin.panel.nav.refresh")

    /** Intent payload key carrying the acted-on player's UUID. */
    public const val PAYLOAD_TARGET: String = "target"

    /** Back-to-hub button. */
    public fun back(): GuiSlot.Static = GuiSlot.Static(
        itemId = BACK_ICON,
        onLeftClick = GuiIntent(AdminIntents.HUB_OPEN),
        displayName = GuiText(K_BACK),
    )

    /** Close-window button (`gui.close` is a router built-in). */
    public fun close(): GuiSlot.Static = GuiSlot.Static(
        itemId = CLOSE_ICON,
        onLeftClick = GuiIntent("gui.close"),
        displayName = GuiText(K_CLOSE),
    )

    /** Refresh button re-emitting [intent]. */
    public fun refresh(intent: GuiIntent): GuiSlot.Static = GuiSlot.Static(
        itemId = REFRESH_ICON,
        onLeftClick = intent,
        displayName = GuiText(K_REFRESH),
    )

    /**
     * Place `< page/pages >` on a bottom row: prev at [prevSlot], indicator at
     * [pageSlot], next at [nextSlot]. Prev / next re-emit [key] with
     * `page = page±1` merged over [payload].
     */
    public fun GuiSpec.Builder.pager(
        key: String,
        payload: Map<String, String>,
        page: Int,
        pages: Int,
        prevSlot: Int,
        pageSlot: Int,
        nextSlot: Int,
    ) {
        if (page > 0) {
            slot(
                SlotIndex(prevSlot),
                GuiSlot.Static(
                    itemId = PREV_ICON,
                    onLeftClick = GuiIntent(key, payload + ("page" to (page - 1).toString())),
                    displayName = GuiText(K_PREV),
                ),
            )
        }
        slot(
            SlotIndex(pageSlot),
            GuiSlot.Static(
                itemId = PAGE_ICON,
                displayName = GuiText(K_PAGE, mapOf("page" to (page + 1).toString(), "pages" to pages.toString())),
            ),
        )
        if (page < pages - 1) {
            slot(
                SlotIndex(nextSlot),
                GuiSlot.Static(
                    itemId = NEXT_ICON,
                    onLeftClick = GuiIntent(key, payload + ("page" to (page + 1).toString())),
                    displayName = GuiText(K_NEXT),
                ),
            )
        }
    }

    /** Convert a registry tile into a clickable slot; [payload] is merged into its intent. */
    public fun tileSlot(tile: AdminPanelTile, payload: Map<String, String> = emptyMap()): GuiSlot.Static =
        GuiSlot.Static(
            itemId = tile.icon,
            onLeftClick = tile.intent.copy(payload = tile.intent.payload + payload),
            displayName = tile.name,
            lore = tile.lore,
        )

    /**
     * Show [spec]: update in place when the viewer already has a panel of the
     * same title / size open (page flips keep the cursor), otherwise open it.
     */
    public fun show(adapter: IGuiAdapter, player: IMcPlayer, spec: GuiSpec): Boolean {
        val current = adapter.currentSpec(player)
        if (current != null && current.title == spec.title && current.size == spec.size) {
            adapter.update(player, spec)
            return true
        }
        return adapter.open(player, spec)
    }

    /** Clamp [page] into `0 until pages` (pages is at least 1). */
    public fun clampPage(page: Int, pages: Int): Int = page.coerceIn(0, (pages - 1).coerceAtLeast(0))

    /** Number of pages needed for [count] entries at [perPage]; at least 1. */
    public fun pageCount(count: Int, perPage: Int): Int = ((count + perPage - 1) / perPage).coerceAtLeast(1)
}
