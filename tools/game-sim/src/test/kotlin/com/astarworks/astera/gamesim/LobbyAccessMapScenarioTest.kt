package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.config.LobbyNpcYamlConfig
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IGuiRegistry
import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.player.ControlScheme
import com.astarworks.astera.core.domain.player.ParticleVisibility
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UI-01..UI-04 (docs/game-design/access-map.md): four role-hub NPCs, every main-menu tile opens the surface the
 * access map names, the settings screen persists each setting, and the settings/social commands open their GUIs.
 */
class LobbyAccessMapScenarioTest {

    private fun GuiSpec.statics(): List<GuiSlot.Static> = slots.values.filterIsInstance<GuiSlot.Static>()

    @Test
    fun `the lobby has exactly the four role-hub NPCs and each opens its hub`() {
        val npcs = mutableListOf<LobbyNpcYamlConfig>()
        val report = ContentLoader(ContentLoader.DEFAULT_PARSERS, LobbyNpcYamlConfig.serializer())
            .loadFrom(GameSim.defaultContentDir().resolve("lobby-npcs")) { npcs += it }
        assertThat(report.failures).isEmpty()
        assertThat(npcs.associate { it.id to (it.interaction.rightClickIntent to it.interaction.rightClickPayload) }).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "play-npc" to ("lobby.queue.show" to emptyMap()),
                "market-npc" to ("lobby.shop.open" to emptyMap()),
                "travel-npc" to ("lobby.travel.show" to emptyMap()),
                "social-npc" to ("gui.open" to mapOf("gui" to "social-hub")),
            ),
        )
    }

    @Test
    fun `every main-menu tile opens the surface the access map names`() {
        scenario("access-map-menu-tiles") {
            val p = player("walker")
            val router = sim.services.getOrThrow<IGuiIntentRouter>()
            val adapter = sim.services.getOrThrow<IGuiAdapter>()
            val registry = sim.services.getOrThrow<IGuiRegistry>()
            val view = checkNotNull(sim.server.view(p.id))
            val menu = checkNotNull(registry.find(com.astarworks.astera.core.domain.gui.GuiId("main-menu")))

            // item -> GUI id it must open (GUI-backed tiles); the rest only need a live handler.
            val opens = mapOf(
                "gui-tile-settings" to "settings",
                "gui-tile-party" to "party-manager",
                "gui-tile-friends" to "friends-list",
                "gui-tile-achievements" to "achievement-viewer",
                "gui-tile-travel" to "travel-menu",
                "gui-tile-profile" to "profile-card",
            )
            val player = menu.statics().filter { it.visibleIf?.startsWith("permission:") != true }
            assertThat(player.map { it.itemId.value }).contains(
                "gui-tile-queue", "gui-tile-shop", "gui-tile-vault", "gui-tile-travel", "gui-tile-training", "gui-tile-profile",
                "gui-tile-settings", "gui-tile-party", "gui-tile-friends", "gui-tile-achievements", "gui-tile-quests", "gui-tile-help",
            )
            assertThat(menu.statics().map { it.itemId.value }).doesNotContain("gui-tile-customize")
            for (tile in player) {
                val intent = checkNotNull(tile.onLeftClick) { "${tile.itemId.value} has no click intent" }
                assertThat(router.hasHandler(intent.key)).`as`("${tile.itemId.value} -> ${intent.key}").isTrue()
                val expected = opens[tile.itemId.value] ?: continue
                router.dispatch(view, GuiIntent("gui.open", mapOf("gui" to "main-menu")))
                router.dispatch(view, intent)
                assertThat(adapter.currentSpec(view)?.id?.value).`as`("${tile.itemId.value} opens").isEqualTo(expected)
            }
        }
    }

    @Test
    fun `the social and settings commands open their screens and the travel hub owns training`() {
        scenario("access-map-commands") {
            val p = player("cmd")
            val adapter = sim.services.getOrThrow<IGuiAdapter>()
            val view = checkNotNull(sim.server.view(p.id))

            assertThat(sim.command("cmd", "settings").succeeded).isTrue()
            assertThat(adapter.currentSpec(view)?.id?.value).isEqualTo("settings")
            assertThat(sim.command("cmd", "social").succeeded).isTrue()
            assertThat(adapter.currentSpec(view)?.id?.value).isEqualTo("social-hub")
            assertThat(sim.command("cmd", "menu").succeeded).isTrue()
            assertThat(adapter.currentSpec(view)?.id?.value).isEqualTo("main-menu")

            sim.services.getOrThrow<IGuiIntentRouter>().dispatch(view, GuiIntent("lobby.travel.show"))
            val travel = checkNotNull(adapter.currentSpec(view))
            assertThat(travel.statics().mapNotNull { it.onLeftClick?.key }).contains("lobby.training.travel")
        }
    }

    @Test
    fun `settings tiles persist the player settings that had no screen`() {
        scenario("access-map-settings", persistenceOverlay = true) {
            val p = player("tuner")
            val router = sim.services.getOrThrow<IGuiIntentRouter>()
            val adapter = sim.services.getOrThrow<IGuiAdapter>()
            val settings = sim.services.getOrThrow<IPlayerSettingsRepository>()
            val view = checkNotNull(sim.server.view(p.id))
            fun toggle(key: String) = router.dispatch(view, GuiIntent("settings.toggle", mapOf("key" to key)))

            assertThat(settings.findOrDefault(p.id).particleVisibility).isEqualTo(ParticleVisibility.All)
            toggle("particles")
            assertThat(settings.findOrDefault(p.id).particleVisibility).isEqualTo(ParticleVisibility.OwnTeamOnly)
            assertThat(adapter.currentSpec(view)?.id?.value).`as`("toggling reopens the settings screen").isEqualTo("settings")
            toggle("particles")
            toggle("particles")
            assertThat(settings.findOrDefault(p.id).particleVisibility).isEqualTo(ParticleVisibility.All)

            assertThat(settings.findOrDefault(p.id).doubleJumpEnabled).isTrue()
            toggle("double-jump")
            assertThat(settings.findOrDefault(p.id).doubleJumpEnabled).isFalse()

            assertThat(settings.findOrDefault(p.id).publicProfileEnabled).isFalse()
            toggle("public-profile")
            assertThat(settings.findOrDefault(p.id).publicProfileEnabled).isTrue()

            router.dispatch(view, GuiIntent("settings.volume.adjust", mapOf("channel" to "sound")))
            assertThat(settings.findOrDefault(p.id).soundVolume).isEqualTo(75)
            assertThat(settings.findOrDefault(p.id).musicVolume).isEqualTo(100)
            router.dispatch(view, GuiIntent("settings.controls.cycle"))
            assertThat(settings.findOrDefault(p.id).controlScheme).isEqualTo(ControlScheme.Button)

            // quick-cast: the slot picker is reachable from Settings and each slot tile has a live handler
            val registry = sim.services.getOrThrow<IGuiRegistry>()
            val settingsSpec = checkNotNull(registry.find(com.astarworks.astera.core.domain.gui.GuiId("settings")))
            assertThat(settingsSpec.statics().map { it.onLeftClick?.payload?.get("gui") }).contains("quickcast-slots")
            val quickcast = checkNotNull(registry.find(com.astarworks.astera.core.domain.gui.GuiId("quickcast-slots")))
            assertThat(quickcast.statics().mapNotNull { it.onLeftClick?.key }.filter { it.startsWith("lobby.hotbar.bind.") }).hasSize(9)
        }
    }
}
