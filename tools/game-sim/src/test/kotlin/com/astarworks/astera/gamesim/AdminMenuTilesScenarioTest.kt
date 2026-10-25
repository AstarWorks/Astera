package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.contents.LobbyMenuKitContents
import com.astarworks.astera.core.application.gui.ViewerVisibility
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.service.admin.InMemoryAdminPlayerGateway
import com.astarworks.astera.core.application.port.outbound.admin.IAdminPlayerGateway
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.lobby.KitArea
import com.astarworks.astera.core.domain.permission.RoleId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0240: the main menu and the lobby kit show an Admin tile and an Item catalog tile only to
 * operators / `astera.admin.panel` holders, through the real wired object graph.
 */
class AdminMenuTilesScenarioTest {

    private val adminTiles = setOf("gui-tile-admin", "gui-tile-item-catalog")

    private fun ScenarioScope.openMainMenu(who: SimEntity): GuiSpec {
        val view = checkNotNull(sim.server.view(who.id))
        sim.services.getOrThrow<IGuiIntentRouter>().dispatch(view, GuiIntent("gui.open", mapOf("gui" to "main-menu")))
        return checkNotNull(sim.services.getOrThrow<IGuiAdapter>().currentSpec(view)) { "main-menu did not open for ${who.name}" }
    }

    private fun GuiSpec.statics(): List<GuiSlot.Static> = slots.values.filterIsInstance<GuiSlot.Static>()

    private fun GuiSpec.tile(item: String): GuiSlot.Static? = statics().firstOrNull { it.itemId.value == item }

    @Test
    fun `an admin sees both tiles, the catalog tile opens the items catalog and the admin tile the hub`() {
        scenario("admin-menu-tiles-admin") {
            val admin = player("admin")
            val r = sim.services.getOrThrow<IPermissionService>().assign(admin.id, RoleId("admin"))
            check(r is Result.Success) { "assign admin: $r" }

            val menu = openMainMenu(admin)
            assertThat(menu.id.value).isEqualTo("main-menu")
            assertThat(menu.statics().map { it.itemId.value }).contains("gui-tile-queue", *adminTiles.toTypedArray())

            val view = checkNotNull(sim.server.view(admin.id))
            val router = sim.services.getOrThrow<IGuiIntentRouter>()
            val adapter = sim.services.getOrThrow<IGuiAdapter>()

            router.dispatch(view, checkNotNull(menu.tile("gui-tile-item-catalog")?.onLeftClick))
            assertThat(adapter.currentSpec(view)!!.id.value).isEqualTo("admin-items")

            openMainMenu(admin)
            router.dispatch(view, checkNotNull(menu.tile("gui-tile-admin")?.onLeftClick))
            assertThat(adapter.currentSpec(view)!!.id.value).isEqualTo("admin-hub")
        }
    }

    @Test
    fun `a server operator without a role also sees the tiles`() {
        scenario("admin-menu-tiles-op") {
            val op = player("op")
            (sim.services.getOrThrow<IAdminPlayerGateway>() as InMemoryAdminPlayerGateway).op(op.id)

            assertThat(openMainMenu(op).statics().map { it.itemId.value }).contains(*adminTiles.toTypedArray())
        }
    }

    @Test
    fun `a normal player sees neither tile and cannot open the catalog`() {
        scenario("admin-menu-tiles-player") {
            val rookie = player("rookie")

            val menu = openMainMenu(rookie)
            assertThat(menu.statics().map { it.itemId.value })
                .contains("gui-tile-queue", "gui-tile-shop")
                .doesNotContain(*adminTiles.toTypedArray())

            val view = checkNotNull(sim.server.view(rookie.id))
            sim.services.getOrThrow<IGuiIntentRouter>()
                .dispatch(view, GuiIntent("admin.items.open", mapOf("category" to "all")))
            assertThat(sim.services.getOrThrow<IGuiAdapter>().currentSpec(view)!!.id.value).isEqualTo("main-menu")
        }
    }

    @Test
    fun `the lobby kit swaps in the admin crafting buttons and the compass hint only for admins`() {
        scenario("admin-menu-tiles-kit") {
            val admin = player("admin")
            val rookie = player("rookie")
            val r = sim.services.getOrThrow<IPermissionService>().assign(admin.id, RoleId("admin"))
            check(r is Result.Success) { "assign admin: $r" }

            val contents = LobbyMenuKitContents()
            assertThat(contents.reload(GameSim.defaultContentDir()).failed).isZero()
            val visibility = sim.services.getOrThrow<ViewerVisibility>()

            val adminKit = contents.current().forViewer { visibility.hasPermission(admin.id, it) }
            assertThat(adminKit.at(KitArea.CRAFTING_GRID, 2)?.intent?.key).isEqualTo("admin.hub.open")
            assertThat(adminKit.at(KitArea.CRAFTING_GRID, 3)?.intent?.key).isEqualTo("admin.items.open")
            assertThat(adminKit.at(KitArea.HOTBAR, 8)!!.extraLoreFor { visibility.hasPermission(admin.id, it) }.map { it.value })
                .containsExactly("astera.lobby-kit.hint.admin")

            val rookieKit = contents.current().forViewer { visibility.hasPermission(rookie.id, it) }
            assertThat(rookieKit.at(KitArea.CRAFTING_GRID, 2)?.intent?.key).isEqualTo("lobby.shop.open")
            assertThat(rookieKit.at(KitArea.CRAFTING_GRID, 3)?.intent?.key).isEqualTo("gui.open")
            assertThat(rookieKit.at(KitArea.HOTBAR, 8)!!.extraLoreFor { visibility.hasPermission(rookie.id, it) }).isEmpty()
            assertThat(rookieKit.entries.map { it.intent.key }).doesNotContain("admin.hub.open", "admin.items.open")
        }
    }
}
