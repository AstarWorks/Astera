package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.contents.LobbyMenuKitContents
import com.astarworks.astera.core.application.gui.lobby.CustomizeMenuGuiSpec
import com.astarworks.astera.core.application.gui.lobby.MainMenuGuiSpec
import com.astarworks.astera.core.application.gui.lobby.ProfileCardGuiSpec
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IGuiRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.lobby.KitArea
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** F3-3: no GUI button may emit an intent that nothing handles, and the lobby buttons really open their targets. */
class GuiIntentCoverageScenarioTest {

    private fun intentsOf(spec: GuiSpec): List<Pair<String, GuiIntent>> = spec.slots.values.flatMap { slot ->
        val intents: List<GuiIntent?> = when (slot) {
            is GuiSlot.Static -> listOf(slot.onLeftClick, slot.onRightClick, slot.onShiftClick, slot.onMiddleClick)
            is GuiSlot.Confirmation -> listOf(slot.confirmIntent, slot.cancelIntent)
            is GuiSlot.TextInput -> listOf(slot.onSubmitIntent)
            is GuiSlot.Reactive, is GuiSlot.Border, GuiSlot.Empty -> emptyList()
        }
        intents.filterNotNull().map { spec.id.value to it }
    }

    @Test
    fun `every intent in content gui yaml, the lobby kit and the built-in lobby specs has a registered handler`() {
        scenario("gui-intent-coverage") {
            val router = sim.services.getOrThrow<IGuiIntentRouter>()
            val registry = sim.services.getOrThrow<IGuiRegistry>()

            val specs = registry.all().toList() +
                listOf(MainMenuGuiSpec.SPEC, CustomizeMenuGuiSpec.SPEC, ProfileCardGuiSpec.build(emptySnapshot()))
            assertThat(specs.map { it.id.value }).contains("main-menu", "settings", "friends-list", "party-manager")

            val kit = LobbyMenuKitContents()
            val report = kit.reload(GameSim.defaultContentDir())
            assertThat(report.failed).isZero()

            val emitted = specs.flatMap { intentsOf(it) }.map { (owner, intent) -> "gui:$owner" to intent } +
                kit.current().entries.map { "kit:${it.id}" to it.intent }
            assertThat(emitted).isNotEmpty()

            val unhandled = emitted.filterNot { (_, intent) -> router.hasHandler(intent.key) }
                .map { (owner, intent) -> "$owner -> ${intent.key}" }
            assertThat(unhandled).`as`("GUI buttons whose intent has no handler").isEmpty()

            val knownGuis = specs.map { it.id.value }.toSet()
            val badOpen = emitted.map { it.second }
                .filter { it.key == "gui.open" }
                .mapNotNull { it.payload["gui"] }
                .filterNot { it in knownGuis }
            assertThat(badOpen).`as`("gui.open targets that are not registered").isEmpty()
        }
    }

    @Test
    fun `profile and customize buttons open their targets and profile shows live data`() {
        scenario("gui-lobby-buttons") {
            val p = player("pilot")
            val router = sim.services.getOrThrow<IGuiIntentRouter>()
            val adapter = sim.services.getOrThrow<IGuiAdapter>()
            val view = checkNotNull(sim.server.view(p.id))

            router.dispatch(view, GuiIntent("lobby.customize.open"))
            assertThat(adapter.currentSpec(view)!!.id.value).isEqualTo("customize-menu")

            router.dispatch(view, GuiIntent("lobby.profile.show"))
            val profile = adapter.currentSpec(view)!!
            assertThat(profile.id.value).isEqualTo("profile-card")
            val texts = profile.slots.values.filterIsInstance<GuiSlot.Static>()
                .flatMap { s -> listOfNotNull(s.displayName) + s.lore }
            assertThat(texts.map { it.key.value }).contains(
                "astera.gui.profile-card.level.name",
                "astera.gui.profile-card.matches.played",
                "astera.gui.profile-card.wallet.gold",
            )
        }
    }

    @Test
    fun `ADR-0232 hotbar kit is one item and the F, sneak+F keybinds open the main menu and the vault`() {
        scenario("gui-lobby-keybinds") {
            val p = player("keys")
            val router = sim.services.getOrThrow<IGuiIntentRouter>()
            val adapter = sim.services.getOrThrow<IGuiAdapter>()
            val view = checkNotNull(sim.server.view(p.id))
            val kit = LobbyMenuKitContents().also { assertThat(it.reload(GameSim.defaultContentDir()).failed).isZero() }.current()

            assertThat(kit.entries(KitArea.HOTBAR)).hasSize(1)
            router.dispatch(view, kit.at(KitArea.KEY_SWAP, 0)!!.intent)
            assertThat(adapter.currentSpec(view)!!.id.value).isEqualTo("main-menu")
            router.dispatch(view, kit.at(KitArea.KEY_SNEAK_SWAP, 0)!!.intent)
            assertThat(adapter.currentSpec(view)!!.id.value).isEqualTo("customize-menu")
            assertThat(router.hasHandler(kit.at(KitArea.KEY_DROP, 0)!!.intent.key)).isTrue()
        }
    }

    private fun emptySnapshot() = ProfileCardGuiSpec.Snapshot(1, 0, 0, 0, 0, 0, 0, 0, 0, 0)
}
