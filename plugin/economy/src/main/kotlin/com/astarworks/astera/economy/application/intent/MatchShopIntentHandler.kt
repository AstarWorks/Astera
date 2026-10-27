package com.astarworks.astera.economy.application.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.item.IOwnedItemStock
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.GuiText
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.usecase.shop.MatchShopError
import com.astarworks.astera.economy.application.usecase.shop.MatchShopPurchaseUseCase
import com.astarworks.astera.economy.domain.matchshop.MatchShopCatalog
import com.astarworks.astera.economy.domain.matchshop.MatchShopOffer
import com.astarworks.astera.economy.domain.matchshop.MatchShopTab
import org.slf4j.LoggerFactory

/**
 * The in-match ore shop GUI and its `matchshop.*` intents.
 *
 * - `matchshop.open {team}`: bound on the shop NPC of that team's base. Only a player of that team gets the GUI; the
 *   enemy team and players outside a match get a message.
 * - `matchshop.tab {tab}`: switches the open GUI to another tab.
 * - `matchshop.buy {tab, index}`: buys the offer at [index] of the tab through [MatchShopPurchaseUseCase] and repaints.
 */
public class MatchShopIntentHandler(
    private val catalog: () -> MatchShopCatalog,
    private val purchase: MatchShopPurchaseUseCase,
    private val adapter: IGuiAdapter,
    private val server: IMcServer,
    private val messages: IMessageRenderer,
    private val players: IPlayerGateway,
    private val teams: IMatchTeamLookup,
    private val stock: () -> IOwnedItemStock?,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        when (intent.key) {
            INTENT_OPEN -> open(intent, player)
            INTENT_TAB -> tab(intent, player)?.let { render(player, it) }
            INTENT_BUY -> buy(intent, player)
            else -> log.warn("Unknown matchshop intent '{}' from {}", intent.key, player.id)
        }
    }

    private fun open(intent: GuiIntent, player: IMcPlayer) {
        val team = teams.teamOf(player.id)
        if (team == null) {
            send(player, MSG_NOT_IN_MATCH)
            return
        }
        val shopTeam = intent.payload[PAYLOAD_TEAM]
        if (shopTeam != null && team.value != shopTeam) {
            send(player, MSG_WRONG_TEAM, mapOf("team" to shopTeam))
            return
        }
        render(player, MatchShopTab.WEAPONS)
    }

    private fun tab(intent: GuiIntent, player: IMcPlayer): MatchShopTab? =
        intent.payload[PAYLOAD_TAB]?.let(MatchShopTab::fromKey).also {
            if (it == null) log.warn("matchshop intent without a valid tab from {}: {}", player.id, intent.payload)
        }

    private fun buy(intent: GuiIntent, player: IMcPlayer) {
        val tab = tab(intent, player) ?: return
        val offer = intent.payload[PAYLOAD_INDEX]?.toIntOrNull()?.let { catalog().tab(tab).getOrNull(it) }
        if (offer == null) {
            send(player, MSG_FAILED)
            return
        }
        when (val result = purchase.execute(player.id, offer)) {
            is Result.Success -> send(player, MSG_BOUGHT, mapOf("item" to offer.itemId.value, "amount" to offer.stack.toString()))
            is Result.Failure -> when (val e = result.error) {
                is MatchShopError.NotEnoughOre -> send(
                    player,
                    MSG_SHORT,
                    mapOf("ore" to oreName(player.id, e.ore), "need" to e.need.toString(), "have" to e.have.toString()),
                )
                MatchShopError.NotInMatch -> send(player, MSG_NOT_IN_MATCH)
                MatchShopError.PlayerNotOnline -> Unit
                is MatchShopError.DeliveryFailed -> {
                    log.warn("match shop delivery failed for {}: {}", player.id, e.detail)
                    send(player, MSG_FAILED)
                }
            }
        }
        render(player, tab)
    }

    private fun render(player: IMcPlayer, tab: MatchShopTab) {
        val spec = buildGui(player.id, tab)
        val open = adapter.currentSpec(player)?.id == GUI_ID
        if (open) adapter.update(player, spec) else adapter.open(player, spec)
    }

    private fun buildGui(playerId: PlayerId, current: MatchShopTab): GuiSpec {
        val offers = catalog().tab(current).take(MAX_OFFERS)
        val rows = ((offers.size + 8) / 9).coerceAtLeast(1)
        val size = GuiSize.entries.first { it.slotCount == (rows + 1) * 9 }
        val footer = size.slotCount - 9
        return GuiSpec.build(id = GUI_ID, title = TITLE, size = size) {
            offers.forEachIndexed { index, offer -> slot(SlotIndex(index), offerSlot(playerId, current, index, offer)) }
            for (col in 0 until 9) slot(SlotIndex(footer + col), GuiSlot.Border(FOOTER_PANE))
            MatchShopTab.entries.forEachIndexed { i, tab -> slot(SlotIndex(footer + 1 + i * 2), tabSlot(tab, tab == current)) }
        }
    }

    private fun tabSlot(tab: MatchShopTab, selected: Boolean): GuiSlot.Static = GuiSlot.Static(
        itemId = TAB_ICONS.getValue(tab),
        displayName = GuiText(MessageKey("astera.economy.matchshop.tab.${tab.key}")),
        lore = listOf(GuiText(MessageKey(if (selected) "astera.economy.matchshop.tab.current" else "astera.economy.matchshop.tab.switch"))),
        onLeftClick = GuiIntent(INTENT_TAB, mapOf(PAYLOAD_TAB to tab.key)),
    )

    private fun offerSlot(playerId: PlayerId, tab: MatchShopTab, index: Int, offer: MatchShopOffer): GuiSlot.Static {
        val owned = stock()
        val affordable = owned != null && offer.price.all { (ore, count) -> owned.count(playerId, ore) >= count }
        val price = offer.price.entries.joinToString(" ") { (ore, count) -> "${oreName(playerId, ore)} x$count" }
        return GuiSlot.Static(
            itemId = offer.itemId,
            lore = listOf(
                GuiText(SPACER),
                GuiText(LORE_PRICE, mapOf("price" to price)),
                GuiText(LORE_STACK, mapOf("amount" to offer.stack.toString())),
                GuiText(if (affordable) LORE_BUY else LORE_SHORT),
            ),
            onLeftClick = GuiIntent(INTENT_BUY, mapOf(PAYLOAD_TAB to tab.key, PAYLOAD_INDEX to index.toString())),
        )
    }

    private fun oreName(playerId: PlayerId, ore: ItemId): String =
        messages.render(playerId, MessageKey("astera.economy.matchshop.ore.${ore.value}"))

    private fun send(player: IMcPlayer, key: MessageKey, placeholders: Map<String, String> = emptyMap()) {
        players.sendMessage(player.id, messages.render(player.id, key, placeholders))
    }

    public companion object {
        public const val INTENT_PREFIX: String = "matchshop."
        public const val INTENT_OPEN: String = "matchshop.open"
        public const val INTENT_TAB: String = "matchshop.tab"
        public const val INTENT_BUY: String = "matchshop.buy"
        public const val PAYLOAD_TEAM: String = "team"
        private const val PAYLOAD_TAB = "tab"
        private const val PAYLOAD_INDEX = "index"
        private const val MAX_OFFERS = 45

        private val GUI_ID = GuiId("match-shop")
        private val TITLE = MessageKey("astera.economy.matchshop.title")
        private val FOOTER_PANE = ItemId("gui-pane-dark")
        private val SPACER = MessageKey("astera.economy.shop.gui.spacer")
        private val LORE_PRICE = MessageKey("astera.economy.matchshop.lore.price")
        private val LORE_STACK = MessageKey("astera.economy.matchshop.lore.stack")
        private val LORE_BUY = MessageKey("astera.economy.matchshop.lore.buy")
        private val LORE_SHORT = MessageKey("astera.economy.matchshop.lore.short")
        private val MSG_BOUGHT = MessageKey("astera.economy.matchshop.bought")
        private val MSG_SHORT = MessageKey("astera.economy.matchshop.short")
        private val MSG_WRONG_TEAM = MessageKey("astera.economy.matchshop.wrong-team")
        private val MSG_NOT_IN_MATCH = MessageKey("astera.economy.matchshop.not-in-match")
        private val MSG_FAILED = MessageKey("astera.economy.matchshop.failed")
        private val TAB_ICONS: Map<MatchShopTab, ItemId> = mapOf(
            MatchShopTab.WEAPONS to ItemId("dagger"),
            MatchShopTab.TOOLS to ItemId("iron-pickaxe"),
            MatchShopTab.BLOCKS to ItemId("iron-shovel"),
            MatchShopTab.ARMOR to ItemId("gui-admin-cat-all"),
        )
    }
}
