package com.astarworks.astera.economy.application.cosmetic

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerUnlockStore
import com.astarworks.astera.core.application.service.cosmetic.CosmeticService
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.cosmetic.Cosmetic
import com.astarworks.astera.core.domain.cosmetic.CosmeticKind
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
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOfferId
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * The cosmetics menu (ECO-02 / ECO-04): a GOLD sink beyond weapons and the place a player equips
 * titles, name colours and kill effects. Opened from the profile card (`cosmetic.open`) and
 * `/astera cosmetics`.
 *
 * One click does the obvious thing: an owned cosmetic is equipped (clicking the equipped one takes it
 * off), a sold one is bought (priced at [CONFIRM_AT] or more needs a confirming second click, like the
 * weapon shop), a grant-only one tells where it comes from. Purchases debit GOLD, grant the
 * `title:` / `cosmetic:` entitlement and are recorded as a `CurrencyChanged` under shop `cosmetics`.
 */
public class CosmeticMenu(
    private val cosmetics: CosmeticService,
    private val unlocks: () -> IPlayerUnlockStore?,
    private val ledger: ICurrencyLedger,
    private val eventBus: IDomainEventBus,
    private val adapter: IGuiAdapter,
    private val server: IMcServer,
    private val messages: IMessageRenderer,
    private val players: IPlayerGateway,
    private val clock: Clock = Clock.systemUTC(),
    private val presentation: () -> com.astarworks.astera.core.application.port.outbound.presentation.IPresentation? = { null },
) {
    private data class Pending(val cosmeticId: String, val expiresAtMs: Long)

    private val pending = ConcurrentHashMap<PlayerId, Pending>()

    /** Open (or repaint, when already open) the menu for [playerId]. False when the player is offline or the GUI was refused. */
    public suspend fun open(playerId: PlayerId, pendingId: String? = null): Boolean {
        val player = server.findPlayer(playerId) ?: return false
        val spec = build(playerId, pendingId)
        return if (adapter.currentSpec(player)?.id == GUI_ID) {
            adapter.update(player, spec)
            true
        } else {
            adapter.open(player, spec)
        }
    }

    public suspend fun handle(intent: GuiIntent, player: IMcPlayer) {
        when (intent.key) {
            INTENT_OPEN -> if (!open(player.id)) send(player.id, MSG_ERROR)
            INTENT_CLICK -> click(player.id, intent.payload["id"].orEmpty())
            INTENT_UNEQUIP -> {
                val kind = runCatching { CosmeticKind.valueOf(intent.payload["kind"].orEmpty()) }.getOrNull()
                if (kind != null) {
                    cosmetics.unequip(player.id, kind)
                    send(player.id, MSG_UNEQUIPPED, mapOf("kind" to messages.render(player.id, kindKey(kind))))
                }
                open(player.id)
            }
            else -> Unit
        }
    }

    private fun ui(playerId: PlayerId, moment: String) {
        presentation()?.play(moment, playerId)
    }

    private suspend fun click(playerId: PlayerId, id: String) {
        val cosmetic = cosmetics.find(id) ?: return deny(playerId)
        val name = messages.render(playerId, cosmetic.nameKey)
        val price = cosmetic.priceGold
        when {
            cosmetics.owns(playerId, cosmetic) -> {
                if (cosmetics.equipped(playerId, cosmetic.kind)?.id == cosmetic.id) {
                    cosmetics.unequip(playerId, cosmetic.kind)
                    ui(playerId, com.astarworks.astera.core.domain.presentation.PresentationMoments.UI_CONFIRM)
                    send(playerId, MSG_UNEQUIPPED, mapOf("kind" to messages.render(playerId, kindKey(cosmetic.kind))))
                } else {
                    cosmetics.equip(playerId, cosmetic.id)
                    ui(playerId, com.astarworks.astera.core.domain.presentation.PresentationMoments.UI_CONFIRM)
                    send(playerId, MSG_EQUIPPED, mapOf("name" to name))
                }
            }
            price == null -> deny(playerId).also { send(playerId, MSG_REWARD_ONLY, mapOf("name" to name)) }
            else -> {
                val have = ledger.balance(playerId, Currency.GOLD)
                if (have < price) {
                    deny(playerId)
                    send(playerId, MSG_SHORT, mapOf("name" to name, "need" to price.toString(), "have" to have.toString(), "short" to (price - have).toString()))
                } else if (price >= CONFIRM_AT && !armed(playerId, cosmetic.id)) {
                    pending[playerId] = Pending(cosmetic.id, clock.millis() + CONFIRM_WINDOW_MS)
                    send(playerId, MSG_CONFIRM, mapOf("name" to name, "price" to price.toString()))
                    open(playerId, cosmetic.id)
                    return
                } else {
                    pending.remove(playerId)
                    purchase(playerId, cosmetic, price, name)
                }
            }
        }
        open(playerId)
    }

    private fun deny(playerId: PlayerId) =
        ui(playerId, com.astarworks.astera.core.domain.presentation.PresentationMoments.UI_DENY)

    private fun armed(playerId: PlayerId, id: String): Boolean =
        pending[playerId]?.let { it.cosmeticId == id && it.expiresAtMs >= clock.millis() } == true

    private suspend fun purchase(playerId: PlayerId, cosmetic: Cosmetic, price: Long, name: String) {
        val store = unlocks() ?: return send(playerId, MSG_ERROR)
        val debit = ledger.debit(playerId, Currency.GOLD, price)
        if (debit is com.astarworks.astera.core.domain.Result.Failure) return send(playerId, MSG_ERROR)
        val balance = (debit as com.astarworks.astera.core.domain.Result.Success).value.balance(Currency.GOLD)
        if (!runCatching { store.grant(playerId, cosmetic.unlockId) }.getOrDefault(false)) {
            ledger.credit(playerId, Currency.GOLD, price) // grant failed: give the GOLD back
            return send(playerId, MSG_ERROR)
        }
        eventBus.publish(
            CurrencyChanged(
                playerId = playerId,
                currency = Currency.GOLD,
                delta = -price,
                newBalance = balance,
                reason = CurrencyChangeReason.ShopPurchase(SHOP_ID, ShopOfferId(cosmetic.id)),
            ),
        )
        cosmetics.equip(playerId, cosmetic.id) // a fresh purchase is worn straight away
        cosmetics.notifyChanged(playerId)
        send(playerId, MSG_BOUGHT, mapOf("name" to name, "price" to price.toString(), "balance" to balance.toString()))
    }

    private suspend fun build(playerId: PlayerId, pendingId: String?): GuiSpec {
        val catalog = cosmetics.catalog()
            .sortedWith(compareBy({ it.kind }, { it.priceGold ?: 0L }, { it.id }))
            .take(MAX_ENTRIES)
        val rows = ((catalog.size + 8) / 9).coerceAtLeast(1)
        val size = GuiSize.entries.first { it.slotCount == (rows + 1) * 9 }
        val footer = size.slotCount - 9
        val have = ledger.balance(playerId, Currency.GOLD)
        val entries = catalog.map { c -> entry(playerId, c, have, pendingId == c.id) }
        return GuiSpec.build(id = GUI_ID, title = MessageKey("astera.cosmetic.gui.title"), size = size) {
            entries.forEachIndexed { index, entry -> slot(SlotIndex(index), entry) }
            for (col in 0 until 9) slot(SlotIndex(footer + col), GuiSlot.Border(FOOTER_PANE))
            CosmeticKind.entries.forEachIndexed { i, kind ->
                slot(
                    SlotIndex(footer + 1 + i * 2),
                    GuiSlot.Static(
                        itemId = FOOTER_PANE_RED,
                        displayName = GuiText(MessageKey("astera.cosmetic.gui.unequip"), mapOf("kind" to messages.render(playerId, kindKey(kind)))),
                        onLeftClick = GuiIntent(INTENT_UNEQUIP, mapOf("kind" to kind.name)),
                    ),
                )
            }
            slot(
                SlotIndex(footer + 7),
                GuiSlot.Static(
                    itemId = WALLET_ICON,
                    displayName = GuiText(MessageKey("astera.cosmetic.gui.wallet"), mapOf("amount" to have.toString())),
                ),
            )
        }
    }

    private suspend fun entry(playerId: PlayerId, c: Cosmetic, have: Long, pending: Boolean): GuiSlot.Static {
        val owned = cosmetics.owns(playerId, c)
        val equipped = owned && cosmetics.equipped(playerId, c.kind)?.id == c.id
        val price = c.priceGold
        val state = when {
            equipped -> GuiText(MessageKey("astera.cosmetic.gui.state.equipped"))
            owned -> GuiText(MessageKey("astera.cosmetic.gui.state.owned"))
            price == null -> GuiText(MessageKey("astera.cosmetic.gui.state.reward-only"))
            have < price -> GuiText(MessageKey("astera.cosmetic.gui.state.short"), mapOf("amount" to price.toString(), "have" to have.toString()))
            pending -> GuiText(MessageKey("astera.cosmetic.gui.state.confirm"), mapOf("amount" to price.toString()))
            else -> GuiText(MessageKey("astera.cosmetic.gui.state.buy"), mapOf("amount" to price.toString()))
        }
        return GuiSlot.Static(
            itemId = when (c.kind) {
                CosmeticKind.TITLE -> ItemId("gui-tile-achievements")
                CosmeticKind.NAME_COLOR -> ItemId("gui-tile-customize")
                CosmeticKind.KILL_EFFECT -> ItemId("gui-tile-particles")
            },
            displayName = GuiText(c.nameKey),
            lore = listOf(GuiText(kindKey(c.kind)), state),
            onLeftClick = GuiIntent(INTENT_CLICK, mapOf("id" to c.id)),
        )
    }

    private fun kindKey(kind: CosmeticKind): MessageKey = MessageKey("astera.cosmetic.kind.${kind.name.lowercase()}")

    private fun send(playerId: PlayerId, key: MessageKey, args: Map<String, String> = emptyMap()) {
        players.sendMessage(playerId, messages.render(playerId, key, args))
    }

    public companion object {
        public const val INTENT_PREFIX: String = "cosmetic."
        public const val INTENT_OPEN: String = "cosmetic.open"
        public const val INTENT_CLICK: String = "cosmetic.click"
        public const val INTENT_UNEQUIP: String = "cosmetic.unequip"

        /** Priced at or above this (GOLD) needs a confirming second click, like the weapon shop. */
        public const val CONFIRM_AT: Long = 100L
        public const val CONFIRM_WINDOW_MS: Long = 8_000L
        public const val MAX_ENTRIES: Int = 45

        public val GUI_ID: GuiId = GuiId("cosmetics")
        public val SHOP_ID: ShopId = ShopId("cosmetics")

        private val FOOTER_PANE = ItemId("gui-pane-dark")
        private val FOOTER_PANE_RED = ItemId("gui-pane-red")
        private val WALLET_ICON = ItemId("gui-admin-currency")
        private val MSG_ERROR = MessageKey("astera.cosmetic.msg.error")
        private val MSG_BOUGHT = MessageKey("astera.cosmetic.msg.bought")
        private val MSG_EQUIPPED = MessageKey("astera.cosmetic.msg.equipped")
        private val MSG_UNEQUIPPED = MessageKey("astera.cosmetic.msg.unequipped")
        private val MSG_CONFIRM = MessageKey("astera.cosmetic.msg.confirm")
        private val MSG_SHORT = MessageKey("astera.cosmetic.msg.short")
        private val MSG_REWARD_ONLY = MessageKey("astera.cosmetic.msg.reward-only")
    }
}
