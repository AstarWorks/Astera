package com.astarworks.astera.items.application.admin

import com.astarworks.astera.core.application.admin.panel.AdminPanelGuard
import com.astarworks.astera.core.application.admin.panel.AdminPanelPoint
import com.astarworks.astera.core.application.admin.panel.InMemoryAdminPanelRegistry
import com.astarworks.astera.core.application.service.DefaultGuiIntentRouter
import com.astarworks.astera.core.application.service.InMemoryAuditLogRepository
import com.astarworks.astera.core.domain.audit.AuditEvent
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.item.ItemTemplate
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.items.application.port.outbound.DeliveryResult
import com.astarworks.astera.items.testfixtures.FakeItemDelivery
import com.astarworks.astera.items.testfixtures.InMemoryItemRegistry
import com.astarworks.astera.items.testfixtures.itemSpec
import com.astarworks.astera.testfixtures.FakeAdminPermissionService
import com.astarworks.astera.testfixtures.FakeGuiAdapter
import com.astarworks.astera.testfixtures.FakeMcPlayer
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class AdminItemsPanelTest {

    private val admin = FakeMcPlayer(PlayerId(UUID.randomUUID()), "admin")
    private val rookie = FakeMcPlayer(PlayerId(UUID.randomUUID()), "rookie")
    private val permissions = FakeAdminPermissionService(mutableSetOf(admin.id))
    private val audit = InMemoryAuditLogRepository()
    private val gui = FakeGuiAdapter()
    private val registry = InMemoryAdminPanelRegistry()
    private val delivery = FakeItemDelivery()
    private val items = InMemoryItemRegistry().apply {
        register(itemSpec("rifle").copy(resourceType = ResourceType.Ammo))
        register(itemSpec("greatsword").copy(resourceType = ResourceType.Energy))
        register(itemSpec("fire-staff", template = ItemTemplate.Vanilla(material = "BLAZE_ROD")).copy(resourceType = ResourceType.Energy))
        register(itemSpec("apple"))
    }
    private val router = DefaultGuiIntentRouter { gui }

    init {
        val guard = AdminPanelGuard(permissions, FakeMessageRenderer(), audit)
        AdminItemsPanel(guard, registry, items, AdminGiveItemsUseCase(items, delivery), gui).install(router)
    }

    @Test
    fun `registers a hub tile for the catalog`() {
        val tile = registry.tiles(AdminPanelPoint.HUB).single()
        assertThat(tile.id).isEqualTo("items.catalog")
        assertThat(tile.intent.key).isEqualTo(AdminItemCatalog.INTENT_OPEN)
    }

    @Test
    fun `non-admin gets nothing and a denial message`() {
        router.dispatch(rookie, GuiIntent(AdminItemCatalog.INTENT_GIVE, mapOf("item" to "rifle", "amount" to "1")))
        router.dispatch(rookie, GuiIntent(AdminItemCatalog.INTENT_KIT))
        router.dispatch(rookie, GuiIntent(AdminItemCatalog.INTENT_OPEN, mapOf("category" to "all")))

        assertThat(delivery.gave).isEmpty()
        assertThat(gui.currentSpec(rookie)).isNull()
        assertThat(rookie.messages).hasSize(3).allMatch { it.contains("astera.admin.panel.denied") }
        assertThat(audit.recent(10)).isEmpty()
    }

    @Test
    fun `left-click give hands the real item to the admin and audits it`() {
        router.dispatch(admin, GuiIntent(AdminItemCatalog.INTENT_GIVE, mapOf("item" to "rifle", "amount" to "1")))

        assertThat(delivery.gave).hasSize(1)
        assertThat(delivery.gave.single().spec.id.value).isEqualTo("rifle")
        assertThat(delivery.gave.single().amount).isEqualTo(1)
        assertThat(delivery.gave.single().target).isSameAs(admin)
        val event = audit.recent(10).single() as AuditEvent.AdminActionPerformed
        assertThat(event.actor).isEqualTo(admin.id)
        assertThat(event.action).isEqualTo(AdminItemCatalog.INTENT_GIVE)
        assertThat(event.target).isEqualTo("rifle")
    }

    @Test
    fun `shift-click amount and unknown item are handled`() {
        router.dispatch(admin, GuiIntent(AdminItemCatalog.INTENT_GIVE, mapOf("item" to "apple", "amount" to "16")))
        assertThat(delivery.gave.single().amount).isEqualTo(16)

        router.dispatch(admin, GuiIntent(AdminItemCatalog.INTENT_GIVE, mapOf("item" to "nope", "amount" to "1")))
        assertThat(delivery.gave).hasSize(1)
        assertThat(admin.messages.last()).contains("astera.admin.panel.items.err.not-found")
    }

    @Test
    fun `full kit gives exactly one of each weapon`() {
        router.dispatch(admin, GuiIntent(AdminItemCatalog.INTENT_KIT))

        assertThat(delivery.gave.map { it.spec.id.value }).containsExactlyInAnyOrder("rifle", "greatsword", "fire-staff")
        assertThat(delivery.gave).allMatch { it.amount == 1 }
    }

    @Test
    fun `category give hands every item of the category`() {
        router.dispatch(admin, GuiIntent(AdminItemCatalog.INTENT_GIVE_CATEGORY, mapOf("category" to "gun")))
        assertThat(delivery.gave.map { it.spec.id.value }).containsExactly("rifle")
    }

    @Test
    fun `full inventory stops the kit and reports how far it got`() {
        delivery.nextResult = DeliveryResult.InventoryFull("admin")
        router.dispatch(admin, GuiIntent(AdminItemCatalog.INTENT_KIT))
        assertThat(admin.messages.last()).contains("astera.admin.panel.items.err.full")
    }

    @Test
    fun `open shows the catalog and a page flip updates in place`() {
        router.dispatch(admin, GuiIntent(AdminItemCatalog.INTENT_OPEN, mapOf("category" to "all", "page" to "0")))
        assertThat(gui.currentSpec(admin)!!.id).isEqualTo(AdminItemCatalog.GUI_ID)
        router.dispatch(admin, GuiIntent(AdminItemCatalog.INTENT_OPEN, mapOf("category" to "gun", "page" to "0")))
        assertThat(gui.history(admin).map { it::class.simpleName }).containsExactly("Open", "Update")
    }
}
