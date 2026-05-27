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
 * Wave 5lobby-content (ADR-0119) — friends list (Phase 1 skeleton).
 *
 * Wave 5e shipped the [com.astarworks.astera.core.domain.social.Friendship]
 * domain + 4 use cases (SendFriendRequest / AcceptFriendRequest /
 * DeclineFriendRequest / RemoveFriend). This wave wires the GUI **surface**
 * that lets a player open them from the hotbar; the per-friend tile binding
 * lands in a future wave once `player.friends.*`
 * [com.astarworks.astera.core.application.port.outbound.IGuiBindingResolver]
 * keys are defined.
 *
 * Layout (`CHEST_54`):
 *  - Slots 0..44: friend tile region — currently empty (renderer fills via
 *    a future `player.friends.list` binding).
 *  - Slot 45 (bottom-left): "Add friend" tile → `lobby.friends.add` intent
 *    (anvil prompt, not yet wired — emits an intent the future
 *    `IGuiModalGateway` will catch).
 *  - Slot 49 (bottom centre): close button (`gui.close`).
 *  - Slot 53 (bottom-right): "Pending requests" tile →
 *    `lobby.friends.pending` (placeholder; future wave handles).
 */
public object FriendsListGuiSpec {

    public val ID: GuiId = GuiId("friends-list")

    public val SPEC: GuiSpec = GuiSpec.build(
        id = ID,
        title = MessageKey("astera.gui.friends-list.title"),
        size = GuiSize.CHEST_54,
    ) {
        slot(
            SlotIndex(45),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-friends"),
                onLeftClick = GuiIntent("lobby.friends.add"),
                tooltip = listOf(MessageKey("astera.gui.friends-list.add.tooltip")),
            ),
        )
        slot(
            SlotIndex(49),
            GuiSlot.Static(
                itemId = ItemId("gui-pane-red"),
                onLeftClick = GuiIntent("gui.close"),
                tooltip = listOf(MessageKey("astera.gui.button.close")),
            ),
        )
        slot(
            SlotIndex(53),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-friends"),
                onLeftClick = GuiIntent("lobby.friends.pending"),
                tooltip = listOf(MessageKey("astera.gui.friends-list.pending.tooltip")),
            ),
        )
    }
}
