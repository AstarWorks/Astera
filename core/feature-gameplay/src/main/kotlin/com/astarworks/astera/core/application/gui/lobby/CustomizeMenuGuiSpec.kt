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
 * Customize sub-menu, opened by `lobby.customize.show` / `lobby.customize.open`.
 * Three tiles in a single 9-slot row:
 *
 *  - Slot 2: personal vault (`lobby.vault.open`) — the ender-chest storage that
 *    holds the player's gear between matches (WP12 replaced the loadout selector).
 *  - Slot 4: profile card (`lobby.profile.show`).
 *  - Slot 6: settings (`lobby.settings.show`).
 */
public object CustomizeMenuGuiSpec {

    public val ID: GuiId = GuiId("customize-menu")

    public val SPEC: GuiSpec = GuiSpec.build(
        id = ID,
        title = MessageKey("astera.gui.customize-menu.title"),
        size = GuiSize.CHEST_9,
    ) {
        slot(
            SlotIndex(2),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-vault"),
                onLeftClick = GuiIntent("lobby.vault.open"),
                tooltip = listOf(MessageKey("astera.gui.customize-menu.vault.tooltip")),
            ),
        )
        slot(
            SlotIndex(4),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-profile"),
                onLeftClick = GuiIntent("lobby.profile.show"),
                tooltip = listOf(MessageKey("astera.gui.customize-menu.profile.tooltip")),
            ),
        )
        slot(
            SlotIndex(6),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-settings"),
                onLeftClick = GuiIntent("lobby.settings.show"),
                tooltip = listOf(MessageKey("astera.gui.customize-menu.settings.tooltip")),
            ),
        )
    }
}
