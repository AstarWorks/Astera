package com.astarworks.astera.items.application.admin

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.admin.panel.AdminIntents
import com.astarworks.astera.core.application.admin.panel.AdminPanelGuard
import com.astarworks.astera.core.application.admin.panel.AdminPanelPoint
import com.astarworks.astera.core.application.admin.panel.AdminPanelTile
import com.astarworks.astera.core.application.admin.panel.AdminPanelUi
import com.astarworks.astera.core.application.admin.panel.IAdminPanelRegistry
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import org.slf4j.LoggerFactory

/**
 * Items-plugin section of the `/astera-admin` panel: the weapons & items
 * catalog (F1-D, ADR-0183). Contributes the hub tile and owns the
 * `admin.items.` intent prefix; every click is permission-checked through
 * [AdminPanelGuard.guarded] and every hand-out is audited.
 */
public class AdminItemsPanel(
    private val guard: AdminPanelGuard,
    private val registry: IAdminPanelRegistry,
    private val items: IItemRegistry,
    private val give: AdminGiveItemsUseCase,
    private val guiAdapter: IGuiAdapter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Register the hub tile and the `admin.items.` handler. */
    public fun install(router: IGuiIntentRouter): List<ServiceRegistry.Registration> = listOf(
        registry.register(
            AdminPanelTile(
                id = "items.catalog",
                point = AdminPanelPoint.HUB,
                order = 10,
                icon = ItemId("gui-admin-weapons"),
                name = GuiText(K_HUB),
                lore = listOf(GuiText(K_HUB_LORE)),
                intent = GuiIntent(AdminItemCatalog.INTENT_OPEN, mapOf(AdminItemCatalog.PAYLOAD_CATEGORY to "all")),
            ),
        ),
        router.register(AdminIntents.ITEMS_PREFIX, guard.guarded(::handle)),
    )

    /** Intent entry point (public so tests can drive it without a router). */
    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        when (intent.key) {
            AdminItemCatalog.INTENT_OPEN -> openCatalog(
                player,
                AdminItemCategory.parse(intent.payload[AdminItemCatalog.PAYLOAD_CATEGORY]),
                intent.payload["page"]?.toIntOrNull() ?: 0,
            )
            AdminItemCatalog.INTENT_GIVE -> giveOne(player, intent)
            AdminItemCatalog.INTENT_GIVE_CATEGORY -> {
                val category = AdminItemCategory.parse(intent.payload[AdminItemCatalog.PAYLOAD_CATEGORY])
                if (category == null) {
                    log.warn("admin.items.give-category without a valid category: {}", intent.payload)
                } else {
                    giveSet(player, AdminItemCatalog.entries(items.all(), category), "category=${category.key}")
                }
            }
            AdminItemCatalog.INTENT_KIT -> giveSet(player, AdminItemCatalog.kit(items.all()), "kit")
            else -> log.warn("Unknown admin.items intent '{}' from {}", intent.key, player.name)
        }
    }

    private fun openCatalog(player: IMcPlayer, filter: AdminItemCategory?, page: Int) {
        val spec = AdminItemCatalog.buildSpec(items.all(), filter, page)
        if (!AdminPanelUi.show(guiAdapter, player, spec)) guard.tell(player, K_OPEN_REJECTED)
    }

    private fun giveOne(player: IMcPlayer, intent: GuiIntent) {
        val itemId = intent.payload[AdminItemCatalog.PAYLOAD_ITEM].orEmpty()
        val amount = intent.payload[AdminItemCatalog.PAYLOAD_AMOUNT]?.toIntOrNull() ?: 1
        when (val r = give.give(player, itemId, amount)) {
            is Result.Success -> {
                guard.tell(player, K_GIVEN, mapOf("item" to itemId, "amount" to amount.toString()))
                guard.record(player, AdminItemCatalog.INTENT_GIVE, itemId, "x$amount")
            }
            is Result.Failure -> guard.tell(player, errorKey(r.error), errorArgs(r.error, itemId))
        }
    }

    private fun giveSet(player: IMcPlayer, specs: List<com.astarworks.astera.core.domain.item.ItemSpec>, label: String) {
        if (specs.isEmpty()) {
            guard.tell(player, K_NOTHING)
            return
        }
        when (val r = give.giveEach(player, specs)) {
            is Result.Success -> {
                guard.tell(player, K_SET_GIVEN, mapOf("count" to r.value.toString()))
                guard.record(player, AdminItemCatalog.INTENT_KIT, null, "$label x${r.value}")
            }
            is Result.Failure -> {
                guard.tell(player, errorKey(r.error), errorArgs(r.error, label))
                (r.error as? AdminGiveError.InventoryFull)?.let {
                    guard.record(player, AdminItemCatalog.INTENT_KIT, null, "$label partial ${it.delivered}/${it.requested}")
                }
            }
        }
    }

    private fun errorKey(e: AdminGiveError): MessageKey = when (e) {
        is AdminGiveError.ItemNotFound -> K_ERR_NOT_FOUND
        is AdminGiveError.InvalidAmount -> K_ERR_AMOUNT
        is AdminGiveError.InventoryFull -> K_ERR_FULL
        is AdminGiveError.UnknownTemplate -> K_ERR_TEMPLATE
    }

    private fun errorArgs(e: AdminGiveError, item: String): Map<String, String> = when (e) {
        is AdminGiveError.InventoryFull -> mapOf("delivered" to e.delivered.toString(), "requested" to e.requested.toString())
        is AdminGiveError.UnknownTemplate -> mapOf("template" to e.templateKey)
        else -> mapOf("item" to item)
    }

    private companion object {
        val K_HUB = MessageKey("astera.admin.panel.items.hub")
        val K_HUB_LORE = MessageKey("astera.admin.panel.items.hub.lore")
        val K_GIVEN = MessageKey("astera.admin.panel.items.given")
        val K_SET_GIVEN = MessageKey("astera.admin.panel.items.set-given")
        val K_NOTHING = MessageKey("astera.admin.panel.items.nothing")
        val K_ERR_NOT_FOUND = MessageKey("astera.admin.panel.items.err.not-found")
        val K_ERR_AMOUNT = MessageKey("astera.admin.panel.items.err.amount")
        val K_ERR_FULL = MessageKey("astera.admin.panel.items.err.full")
        val K_ERR_TEMPLATE = MessageKey("astera.admin.panel.items.err.template")
        val K_OPEN_REJECTED = MessageKey("astera.gui.error.open-rejected")
    }
}
