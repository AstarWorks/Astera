package com.astarworks.astera.core.application.admin.panel

import com.astarworks.astera.core.application.port.outbound.admin.AdminGameMode
import com.astarworks.astera.core.application.service.DefaultGuiIntentRouter
import com.astarworks.astera.core.application.service.InMemoryAuditLogRepository
import com.astarworks.astera.core.application.service.InMemoryReloadableContentsRegistry
import com.astarworks.astera.core.application.service.admin.InMemoryAdminPlayerGateway
import com.astarworks.astera.core.application.service.admin.InMemoryServerHealthCheck
import com.astarworks.astera.core.application.service.maintenance.InMemoryMaintenanceModeService
import com.astarworks.astera.core.application.usecase.admin.ReloadAllContentUseCase
import com.astarworks.astera.core.domain.audit.AuditEvent
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiText
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.maintenance.MaintenanceMode
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeAdminPermissionService
import com.astarworks.astera.testfixtures.FakeGuiAdapter
import com.astarworks.astera.testfixtures.FakeMcPlayer
import com.astarworks.astera.testfixtures.FakeMcServer
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class AdminCorePanelTest {

    private val admin = FakeMcPlayer(PlayerId(UUID.randomUUID()), "admin")
    private val rookie = FakeMcPlayer(PlayerId(UUID.randomUUID()), "rookie")
    private val permissions = FakeAdminPermissionService(mutableSetOf(admin.id))
    private val audit = InMemoryAuditLogRepository()
    private val gui = FakeGuiAdapter()
    private val registry = InMemoryAdminPanelRegistry()
    private val players = InMemoryAdminPlayerGateway().apply {
        join(admin.id, "admin")
        join(rookie.id, "rookie")
    }
    private val maintenance = InMemoryMaintenanceModeService()
    private val server = FakeMcServer().apply { register(admin); register(rookie) }
    private val router = DefaultGuiIntentRouter { gui }
    private val panel: AdminCorePanel
    private val guard = AdminPanelGuard(permissions, FakeMessageRenderer(), audit, isOperator = players::isOperator)

    init {
        panel = AdminCorePanel(
            guard, registry, gui, players, maintenance, InMemoryServerHealthCheck(),
            ReloadAllContentUseCase(InMemoryReloadableContentsRegistry()),
        )
        panel.install(router)
    }

    private fun target(p: FakeMcPlayer) = mapOf(AdminPanelUi.PAYLOAD_TARGET to p.id.toString())

    @Test
    fun `hub lists registered tiles in order and core registers players server self`() {
        registry.register(
            AdminPanelTile("x.first", AdminPanelPoint.HUB, 5, ItemId("gui-admin-weapons"), GuiText(com.astarworks.astera.core.domain.i18n.MessageKey("a.b")), intent = GuiIntent("admin.x.open")),
        )
        val hub = panel.buildHub()
        val intents = (0..30).mapNotNull { (hub.slotAt(SlotIndex(it)) as? GuiSlot.Static)?.onLeftClick?.key }
        assertThat(intents).containsExactly("admin.x.open", "admin.players.open", "admin.server.open", "admin.self.open", "gui.close")
    }

    @Test
    fun `non-admin clicks are denied for every core area`() {
        listOf(
            GuiIntent(AdminIntents.HUB_OPEN),
            GuiIntent(AdminCorePanel.OPEN_PLAYERS),
            GuiIntent(AdminCorePanel.PLAYERS_KICK, target(admin)),
            GuiIntent(AdminCorePanel.PLAYERS_GAMEMODE, target(admin)),
            GuiIntent(AdminCorePanel.SERVER_MAINTENANCE),
            GuiIntent(AdminCorePanel.SELF_FLY),
        ).forEach { router.dispatch(rookie, it) }

        assertThat(gui.currentSpec(rookie)).isNull()
        assertThat(players.kicks).isEmpty()
        assertThat(maintenance.current()).isInstanceOf(MaintenanceMode.Off::class.java)
        assertThat(players.isFlying(rookie.id)).isFalse()
        assertThat(rookie.messages).hasSize(6).allMatch { it.contains("astera.admin.panel.denied") }
    }

    @Test
    fun `player list paginates 45 heads per page`() {
        repeat(50) { players.join(PlayerId(UUID.randomUUID()), "p%02d".format(it)) }
        val page0 = panel.buildPlayerList(0)
        val page1 = panel.buildPlayerList(1)
        assertThat((0..44).count { page0.slotAt(SlotIndex(it)) is GuiSlot.Static }).isEqualTo(45)
        assertThat((0..44).count { page1.slotAt(SlotIndex(it)) is GuiSlot.Static }).isEqualTo(7) // 52 total
        val head = page0.slotAt(SlotIndex(0)) as GuiSlot.Static
        assertThat(head.headOf).isNotNull()
        assertThat(head.onLeftClick!!.key).isEqualTo(AdminCorePanel.PLAYERS_PICK)
    }

    @Test
    fun `player actions page merges plugin tiles and injects the target`() {
        registry.register(
            AdminPanelTile("c.heal", AdminPanelPoint.PLAYER, 20, ItemId("gui-admin-heal"), GuiText(com.astarworks.astera.core.domain.i18n.MessageKey("a.b")), intent = GuiIntent("admin.combat.heal")),
        )
        val spec = panel.buildPlayerActions(players.onlinePlayers().first { it.name == "rookie" })
        val contributed = (9..26).mapNotNull { spec.slotAt(SlotIndex(it)) as? GuiSlot.Static }
            .first { it.onLeftClick?.key == "admin.combat.heal" }
        assertThat(contributed.onLeftClick!!.payload).containsEntry("target", rookie.id.toString())
        // Kick is shift-click only.
        val kick = (9..26).mapNotNull { spec.slotAt(SlotIndex(it)) as? GuiSlot.Static }
            .first { it.onShiftClick?.key == AdminCorePanel.PLAYERS_KICK }
        assertThat(kick.onLeftClick).isNull()
    }

    @Test
    fun `teleport bring gamemode and kick call the gateway and audit`() {
        router.dispatch(admin, GuiIntent(AdminCorePanel.PLAYERS_TP, target(rookie)))
        router.dispatch(admin, GuiIntent(AdminCorePanel.PLAYERS_BRING, target(rookie)))
        router.dispatch(admin, GuiIntent(AdminCorePanel.PLAYERS_GAMEMODE, target(rookie)))
        router.dispatch(admin, GuiIntent(AdminCorePanel.PLAYERS_KICK, target(rookie)))

        assertThat(players.teleports).containsExactly(admin.id to rookie.id, rookie.id to admin.id)
        assertThat(players.modeOf(rookie.id)).isEqualTo(AdminGameMode.CREATIVE)
        assertThat(players.kicks.map { it.first }).containsExactly(rookie.id)
        assertThat(audit.recent(10).filterIsInstance<AuditEvent.AdminActionPerformed>().map { it.action })
            .containsExactlyInAnyOrder(
                AdminCorePanel.PLAYERS_TP, AdminCorePanel.PLAYERS_BRING,
                AdminCorePanel.PLAYERS_GAMEMODE, AdminCorePanel.PLAYERS_KICK,
            )
    }

    @Test
    fun `an admin cannot kick themselves`() {
        router.dispatch(admin, GuiIntent(AdminCorePanel.PLAYERS_KICK, target(admin)))
        assertThat(players.kicks).isEmpty()
        assertThat(admin.messages.last()).contains("kick.self")
    }

    @Test
    fun `maintenance toggles and the server page reflects it`() {
        router.dispatch(admin, GuiIntent(AdminCorePanel.SERVER_MAINTENANCE))
        assertThat(maintenance.current()).isInstanceOf(MaintenanceMode.On::class.java)
        router.dispatch(admin, GuiIntent(AdminCorePanel.SERVER_MAINTENANCE))
        assertThat(maintenance.current()).isInstanceOf(MaintenanceMode.Off::class.java)
    }

    @Test
    fun `fly toggles for the clicker`() {
        router.dispatch(admin, GuiIntent(AdminCorePanel.SELF_FLY))
        assertThat(players.isFlying(admin.id)).isTrue()
    }

    @Test
    fun `open use case enforces the permission before opening`() {
        val open = OpenAdminPanelUseCase(panel, server, guard)
        assertThat(open.execute(rookie.id)).isInstanceOf(Result.Failure::class.java)
        assertThat(gui.currentSpec(rookie)).isNull()
        assertThat(open.execute(admin.id)).isInstanceOf(Result.Success::class.java)
        assertThat(gui.currentSpec(admin)!!.id).isEqualTo(AdminCorePanel.HUB_ID)
    }

    @Test
    fun `server operators pass the gate without an astera role`() {
        players.op(rookie.id)
        router.dispatch(rookie, GuiIntent(AdminIntents.HUB_OPEN))
        assertThat(gui.currentSpec(rookie)!!.id).isEqualTo(AdminCorePanel.HUB_ID)
    }
}
