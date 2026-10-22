package com.astarworks.astera.core.application.gui.lobby

import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Lobby main-menu GUI (Wave 5wireup, ADR-0116 §G17).
 *
 * Six navigation tiles in a 9-slot single-row chest layout, placed at slot
 * indices 1, 2, 3, 5, 6, 7 (skipping 0/4/8 to centre the row). Each tile is
 * a [GuiSlot.Static] whose left-click dispatches a [GuiIntent] the lobby
 * intent router translates into either a use case (queue join, travel) or
 * an outbound GUI open (shop, refinement, customize, profile).
 *
 * # Item placeholders
 *
 * The tile icons use placeholder `gui-*` item ids. These are author-editable
 * via `content/gui/main-menu.yaml` (Wave 5h reload-aware) — the YAML loader
 * (not part of this wave) will override the spec at content-load time. The
 * Kotlin spec defined here is the runtime fallback when no YAML is present
 * (the standard Wave 5h pattern).
 *
 * # Intent keys
 *
 * - `lobby.queue.show` — opens the queue sub-menu (lists `norma`, `custom:*`,
 *   `tournament:*` options via subsequent intents).
 * - `lobby.shop.open` — dispatches into `ShopBrowseUseCase`.
 * - `lobby.customize.open` — opens the customize sub-menu (vault, profile,
 *   settings).
 * - `lobby.travel.show` — opens the travel sub-menu (pve / survival / lobby
 *   via subsequent intents — matches `/astera travel` subcommands).
 * - `lobby.profile.show` — opens the live profile card (level, XP, stats, wallet).
 *
 * Each intent is routed by the application-layer `IGuiIntentRouter` to the
 * matching use case. New intents are added by extending the router, not by
 * editing this spec — the spec stays declarative.
 */
public object MainMenuGuiSpec {

    public val ID: GuiId = GuiId("main-menu")

    public val SPEC: GuiSpec = GuiSpec.build(
        id = ID,
        title = MessageKey("astera.gui.main-menu.title"),
        size = GuiSize.CHEST_9,
    ) {
        slot(
            SlotIndex(1),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-queue"),
                onLeftClick = GuiIntent("lobby.queue.show"),
                tooltip = listOf(MessageKey("astera.gui.main-menu.queue.tooltip")),
            ),
        )
        slot(
            SlotIndex(2),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-shop"),
                onLeftClick = GuiIntent("lobby.shop.open"),
                tooltip = listOf(MessageKey("astera.gui.main-menu.shop.tooltip")),
            ),
        )
        slot(
            SlotIndex(4),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-customize"),
                onLeftClick = GuiIntent("lobby.customize.open"),
                tooltip = listOf(MessageKey("astera.gui.main-menu.customize.tooltip")),
            ),
        )
        slot(
            SlotIndex(6),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-travel"),
                onLeftClick = GuiIntent("lobby.travel.show"),
                tooltip = listOf(MessageKey("astera.gui.main-menu.travel.tooltip")),
            ),
        )
        slot(
            SlotIndex(7),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-profile"),
                onLeftClick = GuiIntent("lobby.profile.show"),
                tooltip = listOf(MessageKey("astera.gui.main-menu.profile.tooltip")),
            ),
        )
    }
}
