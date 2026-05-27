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
 * Wave 5lobby-content (ADR-0119) — player profile card.
 *
 * Opened by `lobby.profile.show`. Phase 1 PoC layout (`CHEST_27`):
 *
 *  - Slot 4 (centre top): player head tile — visual identity. The Paper
 *    [com.astarworks.astera.core.application.port.outbound.IGuiBindingResolver]
 *    will swap in the live player skin (Wave 6b binding).
 *  - Slot 11: Power Index tile (Wave 5flex `IPowerIndexRepository`).
 *  - Slot 13: Currency tile (Wave 5flex `player.coins.*` binding).
 *  - Slot 15: Recent matches tile (Phase 4 — currently a placeholder).
 *  - Slot 22 (bottom centre): close button (`gui.close`).
 */
public object ProfileCardGuiSpec {

    public val ID: GuiId = GuiId("profile-card")

    public val SPEC: GuiSpec = GuiSpec.build(
        id = ID,
        title = MessageKey("astera.gui.profile-card.title"),
        size = GuiSize.CHEST_27,
    ) {
        slot(
            SlotIndex(4),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-profile"),
                tooltip = listOf(MessageKey("astera.gui.profile-card.head.tooltip")),
            ),
        )
        slot(
            SlotIndex(11),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-profile"),
                tooltip = listOf(MessageKey("astera.gui.profile-card.power-index.tooltip")),
            ),
        )
        slot(
            SlotIndex(13),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-profile"),
                tooltip = listOf(MessageKey("astera.gui.profile-card.currency.tooltip")),
            ),
        )
        slot(
            SlotIndex(15),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-profile"),
                tooltip = listOf(MessageKey("astera.gui.profile-card.recent-matches.tooltip")),
            ),
        )
        slot(
            SlotIndex(22),
            GuiSlot.Static(
                itemId = ItemId("gui-pane-red"),
                onLeftClick = GuiIntent("gui.close"),
                tooltip = listOf(MessageKey("astera.gui.button.close")),
            ),
        )
    }
}
