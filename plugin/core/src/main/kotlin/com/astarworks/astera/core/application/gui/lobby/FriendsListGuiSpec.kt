package com.astarworks.astera.core.application.gui.lobby

import com.astarworks.astera.core.domain.gui.BindingKey
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Wave 5lobby-content (ADR-0119) — friends list. **Updated for Wave M
 * 5polish-quickwin (ADR-0130 §A)** to bind the top row to live friend
 * tiles via `player.friends.list.<n>`.
 *
 * Wave 5e shipped the [com.astarworks.astera.core.domain.social.Friendship]
 * domain + 4 use cases (SendFriendRequest / AcceptFriendRequest /
 * DeclineFriendRequest / RemoveFriend). Wave 5lobby-content wired the
 * lobby GUI surface but left tiles blank; Wave M lands the
 * [com.astarworks.astera.core.application.gui.binding.PlayerFriendsListGuiBindingResolver]
 * that fills the per-friend slots.
 *
 * Layout (`CHEST_54`):
 *  - Slots 0..8: 9 reactive friend tiles bound to
 *    `player.friends.list.0`..`player.friends.list.8`. Phase 1 PoC caps at
 *    9; pagination lands in Phase 2.
 *  - Slots 9..44: empty (reserved for pagination + filter chips).
 *  - Slot 45 (bottom-left): "Add friend" tile → `lobby.friends.add` intent.
 *  - Slot 49 (bottom centre): close button (`gui.close`).
 *  - Slot 53 (bottom-right): "Pending requests" tile →
 *    `lobby.friends.pending`.
 */
public object FriendsListGuiSpec {

    public val ID: GuiId = GuiId("friends-list")

    public const val MAX_FRIEND_TILES: Int = 9

    public val SPEC: GuiSpec = GuiSpec.build(
        id = ID,
        title = MessageKey("astera.gui.friends-list.title"),
        size = GuiSize.CHEST_54,
    ) {
        for (i in 0 until MAX_FRIEND_TILES) {
            slot(
                SlotIndex(i),
                GuiSlot.Reactive(
                    binding = BindingKey("player.friends.list.$i"),
                    tooltip = listOf(MessageKey("astera.gui.friends-list.tile.tooltip")),
                ),
            )
        }
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
