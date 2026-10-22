package com.astarworks.astera.core.application.gui.lobby

import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.GuiText
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Player profile card, opened by `lobby.profile.show` (CHEST_27).
 *
 * Built per click from a [Snapshot] of the viewer's live data (level / XP,
 * combat stats, match record, wallet) — see
 * [com.astarworks.astera.core.application.gui.lobby.intent.LobbyProfileShowIntentHandler].
 */
public object ProfileCardGuiSpec {

    public val ID: GuiId = GuiId("profile-card")

    /** The numbers shown on the card. */
    public data class Snapshot(
        val level: Int,
        val totalXp: Long,
        val kills: Long,
        val deaths: Long,
        val assists: Long,
        val matchesPlayed: Long,
        val wins: Long,
        val losses: Long,
        val star: Long,
        val gold: Long,
        val gem: Long,
    ) {
        /** Win rate in percent, one decimal; 0.0 before the first match. */
        val winRatePercent: String
            get() = if (matchesPlayed <= 0L) {
                "0.0"
            } else {
                "%.1f".format(java.util.Locale.ROOT, wins * 100.0 / matchesPlayed)
            }
    }

    public fun build(snapshot: Snapshot): GuiSpec = GuiSpec.build(
        id = ID,
        title = MessageKey("astera.gui.profile-card.title"),
        size = GuiSize.CHEST_27,
    ) {
        slot(
            SlotIndex(10),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-profile"),
                displayName = GuiText(
                    MessageKey("astera.gui.profile-card.level.name"),
                    mapOf("level" to snapshot.level.toString()),
                ),
                lore = listOf(
                    GuiText(MessageKey("astera.gui.profile-card.level.xp"), mapOf("xp" to snapshot.totalXp.toString())),
                ),
            ),
        )
        slot(
            SlotIndex(12),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-queue"),
                displayName = GuiText(MessageKey("astera.gui.profile-card.combat.name")),
                lore = listOf(
                    GuiText(MessageKey("astera.gui.profile-card.combat.kills"), mapOf("kills" to snapshot.kills.toString())),
                    GuiText(MessageKey("astera.gui.profile-card.combat.deaths"), mapOf("deaths" to snapshot.deaths.toString())),
                    GuiText(MessageKey("astera.gui.profile-card.combat.assists"), mapOf("assists" to snapshot.assists.toString())),
                ),
            ),
        )
        slot(
            SlotIndex(14),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-travel"),
                displayName = GuiText(MessageKey("astera.gui.profile-card.matches.name")),
                lore = listOf(
                    GuiText(MessageKey("astera.gui.profile-card.matches.played"), mapOf("played" to snapshot.matchesPlayed.toString())),
                    GuiText(MessageKey("astera.gui.profile-card.matches.wins"), mapOf("wins" to snapshot.wins.toString())),
                    GuiText(MessageKey("astera.gui.profile-card.matches.losses"), mapOf("losses" to snapshot.losses.toString())),
                    GuiText(MessageKey("astera.gui.profile-card.matches.rate"), mapOf("rate" to snapshot.winRatePercent)),
                ),
            ),
        )
        slot(
            SlotIndex(16),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-shop"),
                displayName = GuiText(MessageKey("astera.gui.profile-card.wallet.name")),
                lore = listOf(
                    GuiText(MessageKey("astera.gui.profile-card.wallet.star"), mapOf("amount" to snapshot.star.toString())),
                    GuiText(MessageKey("astera.gui.profile-card.wallet.gold"), mapOf("amount" to snapshot.gold.toString())),
                    GuiText(MessageKey("astera.gui.profile-card.wallet.gem"), mapOf("amount" to snapshot.gem.toString())),
                ),
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
