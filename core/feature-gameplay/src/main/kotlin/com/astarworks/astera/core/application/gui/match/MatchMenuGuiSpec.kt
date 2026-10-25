package com.astarworks.astera.core.application.gui.match

import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * UI-06 (docs/game-design/access-map.md) - the in-match mini menu's second step. The menu itself is
 * `content/gui/match-menu.yaml` (Settings / Leave / Help); "Leave" opens this confirm screen so a stray
 * click cannot forfeit the match. Slot 2 confirms (`match.menu.leave.confirm`), slot 6 goes back to the menu.
 */
public object MatchMenuGuiSpec {

    public val CONFIRM_ID: GuiId = GuiId("match-menu-confirm")

    public val CONFIRM: GuiSpec = GuiSpec.build(
        id = CONFIRM_ID,
        title = MessageKey("astera.match_menu.confirm.title"),
        size = GuiSize.CHEST_9,
    ) {
        slot(
            SlotIndex(2),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-travel-lobby"),
                onLeftClick = GuiIntent(MatchMenuIntentHandler.INTENT_LEAVE_CONFIRM),
                tooltip = listOf(MessageKey("astera.match_menu.confirm.yes.tooltip")),
            ),
        )
        slot(
            SlotIndex(6),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-back"),
                onLeftClick = GuiIntent("gui.open", mapOf("gui" to MatchMenuIntentHandler.MENU_GUI_ID)),
                tooltip = listOf(MessageKey("astera.match_menu.confirm.no.tooltip")),
            ),
        )
    }
}
