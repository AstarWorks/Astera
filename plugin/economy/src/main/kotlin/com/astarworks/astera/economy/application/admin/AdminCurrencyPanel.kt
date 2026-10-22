package com.astarworks.astera.economy.application.admin

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.admin.panel.AdminIntents
import com.astarworks.astera.core.application.admin.panel.AdminPanelGuard
import com.astarworks.astera.core.application.admin.panel.AdminPanelPoint
import com.astarworks.astera.core.application.admin.panel.AdminPanelTile
import com.astarworks.astera.core.application.admin.panel.AdminPanelUi
import com.astarworks.astera.core.application.admin.panel.IAdminPanelRegistry
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
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
import com.astarworks.astera.economy.application.usecase.currency.DepositUseCase
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Economy section of the `/astera-admin` panel (F1-D, ADR-0182): a per-player
 * "give currency" action with preset amounts. Grants go through
 * [DepositUseCase] (`AdminGrant` reason), so wallets, HUD and the
 * `CurrencyChanged` event stream behave exactly as for `/astera-grant-currency`.
 */
public class AdminCurrencyPanel(
    private val guard: AdminPanelGuard,
    private val registry: IAdminPanelRegistry,
    private val guiAdapter: IGuiAdapter,
    private val deposit: DepositUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun install(router: IGuiIntentRouter): List<ServiceRegistry.Registration> = listOf(
        registry.register(
            AdminPanelTile(
                id = "economy.currency",
                point = AdminPanelPoint.PLAYER,
                order = 50,
                icon = ItemId("gui-admin-currency"),
                name = GuiText(K_TILE),
                lore = listOf(GuiText(K_TILE_LORE)),
                intent = GuiIntent(MENU),
            ),
        ),
        router.register(AdminIntents.CURRENCY_PREFIX, guard.guarded(::handle)),
    )

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        val target = intent.payload[AdminPanelUi.PAYLOAD_TARGET]
            ?.let { runCatching { PlayerId(UUID.fromString(it)) }.getOrNull() }
        if (target == null) {
            guard.tell(player, K_BAD_TARGET)
            return
        }
        when (intent.key) {
            MENU -> if (!AdminPanelUi.show(guiAdapter, player, buildMenu(target))) guard.tell(player, K_OPEN_REJECTED)
            GRANT -> {
                val currency = intent.payload[PAYLOAD_CURRENCY]
                    ?.let { runCatching { Currency.valueOf(it.uppercase()) }.getOrNull() }
                val amount = intent.payload[PAYLOAD_AMOUNT]?.toLongOrNull()
                if (currency == null || amount == null) {
                    guard.tell(player, K_BAD_GRANT)
                    return
                }
                when (val r = deposit.execute(target, currency, amount, CurrencyChangeReason.AdminGrant)) {
                    is Result.Success -> {
                        guard.tell(
                            player,
                            K_GRANTED,
                            mapOf(
                                "amount" to amount.toString(),
                                "currency" to currency.name.lowercase(),
                                "balance" to r.value.balance(currency).toString(),
                            ),
                        )
                        guard.record(player, GRANT, target.toString(), "${currency.name.lowercase()} +$amount")
                    }
                    is Result.Failure -> {
                        log.warn("Admin currency grant failed: {}", r.error)
                        guard.tell(player, K_BAD_GRANT)
                    }
                }
            }
            else -> log.warn("Unknown admin.currency intent '{}' from {}", intent.key, player.name)
        }
    }

    /** Preset grid: one row per currency, one tile per preset amount. */
    public fun buildMenu(target: PlayerId): GuiSpec {
        val targetPayload = mapOf(AdminPanelUi.PAYLOAD_TARGET to target.toString())
        return GuiSpec.build(GUI_ID, K_TITLE, GuiSize.CHEST_36) {
            Currency.entries.forEachIndexed { row, currency ->
                PRESETS.forEachIndexed { col, amount ->
                    slot(
                        SlotIndex(9 + row * 9 + 1 + col),
                        GuiSlot.Static(
                            itemId = ICON_PRESET,
                            count = (col + 1),
                            onLeftClick = GuiIntent(
                                GRANT,
                                targetPayload + mapOf(PAYLOAD_CURRENCY to currency.name.lowercase(), PAYLOAD_AMOUNT to amount.toString()),
                            ),
                            displayName = GuiText(K_PRESET, mapOf("currency" to currency.name.lowercase(), "amount" to amount.toString())),
                        ),
                    )
                }
            }
            slot(
                SlotIndex(27),
                GuiSlot.Static(
                    itemId = AdminPanelUi.BACK_ICON,
                    onLeftClick = GuiIntent("admin.players.pick", targetPayload),
                    displayName = GuiText(AdminPanelUi.K_BACK),
                ),
            )
            slot(SlotIndex(35), AdminPanelUi.close())
        }
    }

    public companion object {
        public const val MENU: String = "admin.currency.menu"
        public const val GRANT: String = "admin.currency.grant"
        public const val PAYLOAD_CURRENCY: String = "currency"
        public const val PAYLOAD_AMOUNT: String = "amount"

        /** Preset grant amounts, left to right. */
        public val PRESETS: List<Long> = listOf(100L, 1_000L, 10_000L, 100_000L)

        public val GUI_ID: GuiId = GuiId("admin-currency")
        private val ICON_PRESET = ItemId("gui-admin-currency")

        private fun k(s: String) = MessageKey("astera.admin.panel.currency.$s")

        private val K_TILE = k("tile")
        private val K_TILE_LORE = k("tile.lore")
        private val K_TITLE = k("title")
        private val K_PRESET = k("preset")
        private val K_GRANTED = k("granted")
        private val K_BAD_TARGET = k("err.bad-target")
        private val K_BAD_GRANT = k("err.bad-grant")
        private val K_OPEN_REJECTED = MessageKey("astera.gui.error.open-rejected")
    }
}
