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
 * Wave 5lobby-content (ADR-0119) — party manager (Phase 1 skeleton).
 *
 * Wave 5e shipped the [com.astarworks.astera.core.domain.social.Party] domain
 * + 4 use cases (CreateParty / JoinParty / LeaveParty / DisbandParty). This
 * wave wires the hotbar GUI surface; per-member tile rendering arrives in
 * a future wave once `player.party.members` bindings land in
 * [com.astarworks.astera.core.application.port.outbound.IGuiBindingResolver].
 *
 * Layout (`CHEST_27`):
 *  - Slots 0..8: member tile region — currently empty (renderer fills via
 *    a future `player.party.members` binding once that wave lands).
 *  - Slot 18 (bottom-left): "Invite player" tile → `lobby.party.invite`.
 *  - Slot 20: "Transfer leader" tile → `lobby.party.transfer-leader`.
 *  - Slot 22 (bottom centre): close button (`gui.close`).
 *  - Slot 24: "Kick member" tile → `lobby.party.kick`.
 *  - Slot 26 (bottom-right): "Leave party" tile → `lobby.party.leave`.
 */
public object PartyManagerGuiSpec {

    public val ID: GuiId = GuiId("party-manager")

    public val SPEC: GuiSpec = GuiSpec.build(
        id = ID,
        title = MessageKey("astera.gui.party-manager.title"),
        size = GuiSize.CHEST_27,
    ) {
        slot(
            SlotIndex(18),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-party"),
                onLeftClick = GuiIntent("lobby.party.invite"),
                tooltip = listOf(MessageKey("astera.gui.party-manager.invite.tooltip")),
            ),
        )
        slot(
            SlotIndex(20),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-party"),
                onLeftClick = GuiIntent("lobby.party.transfer-leader"),
                tooltip = listOf(MessageKey("astera.gui.party-manager.transfer-leader.tooltip")),
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
        slot(
            SlotIndex(24),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-party"),
                onLeftClick = GuiIntent("lobby.party.kick"),
                tooltip = listOf(MessageKey("astera.gui.party-manager.kick.tooltip")),
            ),
        )
        slot(
            SlotIndex(26),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-party"),
                onLeftClick = GuiIntent("lobby.party.leave"),
                tooltip = listOf(MessageKey("astera.gui.party-manager.leave.tooltip")),
            ),
        )
    }
}
