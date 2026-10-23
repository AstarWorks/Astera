package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.inbound.ITutorialSequencerService
import com.astarworks.astera.core.application.port.outbound.*
import com.astarworks.astera.core.application.port.outbound.lobby.ILobbyTrainingRange
import com.astarworks.astera.core.application.service.lobby.LobbyLanePadService
import com.astarworks.astera.core.application.service.tutorial.TutorialGuide
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.TutorialCompleted
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ServerRole
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import com.astarworks.astera.core.domain.tutorial.TutorialStep
import com.astarworks.astera.core.domain.vault.PersonalVault
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TutorialScenarioTest {
    private val lobby = WorldId("lobby")

    @Test
    fun `first join learns every action, resumes after skip and reconnect, and graduates once`() {
        scenario("new-player-guided-tutorial") {
            sim.trainingShells.worldLoaded = true
            val plan = sim.services.getOrThrow<ILobbyTrainingRange>().plan
            var learner = sim.spawnPlayer("learner", at = Vec3(0.5, 65.0, 6.5), worldId = lobby)
            val runner = sim.services.getOrThrow<ITutorialSequencerService>()
            val states = sim.services.getOrThrow<IPlayerTutorialStateRepository>()
            fun action(): String? = (runner.currentStep(learner.id) as? TutorialStep.WaitForAction)?.actionId
            fun intent(key: String) {
                sim.services.getOrThrow<IGuiIntentRouter>().dispatch(checkNotNull(sim.server.view(learner.id)), GuiIntent(key))
            }
            assertThat(action()).isEqualTo("menu") // CreateFirstTimeProfile started it, no direct sequencer start.
            advance(10)
            assertThat(checkNotNull(sim.server.view(learner.id)).titles).contains(TutorialGuide.key("menu", "title"))
            learner.heldItem = null // F is a lobby key only with an empty hand / the compass.
            assertThat(sim.swapHands(learner)).isTrue() // F -> real kit entry -> rendered menu.
            assertThat(action()).isEqualTo("range")
            advance(100)
            assertThat(action()).isEqualTo("range") // waiting is never enough.
            val reconnectId = learner.id
            sim.detachPlayer(reconnectId)
            learner = sim.attachPlayer(reconnectId, "learner").also { it.world = lobby }
            advance(10)
            assertThat(action()).isEqualTo("range")
            assertThat(checkNotNull(sim.server.view(reconnectId)).titles).contains(TutorialGuide.key("range", "title"))
            learner.moveTo(plan.spawn)
            assertThat(action()).isEqualTo("rack")
            val rack = checkNotNull(sim.items.trainingRack)
            assertThat(rack.claim(learner.id, ItemId("knight-sword")).isSuccess).isTrue()
            sim.hold(learner, "knight-sword")
            assertThat(action()).isEqualTo("primary")
            learner.leftClick() // swing at empty space does not satisfy a landed dummy hit.
            advance(12)
            assertThat(action()).isEqualTo("primary")
            val dummy = checkNotNull(sim.trainingShells.bodyOf("melee-1"))
            learner.moveTo(Vec3(dummy.position.x - 1.2, dummy.position.y, dummy.position.z))
            learner.faces(dummy)
            learner.leftClick()
            advance(12)
            assertThat(action()).isEqualTo("secondary")
            val energyBefore = energy(learner)
            learner.rightClick(sneaking = true)
            assertThat(energy(learner)).isLessThan(energyBefore)
            assertThat(action()).isEqualTo("reload")
            assertThat(rack.claim(learner.id, ItemId("pistol")).isSuccess).isTrue()
            sim.hold(learner, "pistol")
            learner.rightClick()
            advance(8)
            assertThat(ammo(learner)).isLessThan(12)
            learner.drop() // Q, real bound reload skill and scheduled refill.
            assertThat(action()).isEqualTo("reload")
            advance(32)
            assertThat(ammo(learner)).isEqualTo(12)
            assertThat(action()).isEqualTo("hud")
            assertThat(foodLevel(learner)).isNotNull()
            assertThat(cooldownSweeps()).isNotEmpty()
            advance(90)
            assertThat(action()).isEqualTo("shop")

            assertThat(sim.command("learner", "tutorial skip").succeeded).isTrue()
            assertThat(states.find(learner.id)?.paused).isTrue()
            val id = learner.id
            sim.detachPlayer(id)
            learner = sim.attachPlayer(id, "learner").also { it.world = lobby; it.position = plan.spawn }
            advance(10)
            assertThat(runner.currentStep(id)).isNull() // skip survives a reconnect.
            assertThat(sim.command("learner", "tutorial status").succeeded).isTrue()
            assertThat(sim.command("learner", "tutorial start").succeeded).isTrue()
            assertThat(action()).isEqualTo("shop")
            val gui = sim.services.getOrThrow<IGuiAdapter>() as com.astarworks.astera.testfixtures.FakeGuiAdapter
            gui.openResult = false
            intent("lobby.shop.open")
            assertThat(action()).isEqualTo("shop")
            gui.openResult = true
            intent("lobby.shop.open")
            advance(10)
            assertThat(action()).isEqualTo("vault")
            intent("lobby.vault.open") // absent host window must not advance.
            assertThat(action()).isEqualTo("vault")
            sim.services.register<IVaultWindow>(object : IVaultWindow {
                override fun show(vault: PersonalVault, page: Int): Boolean = vault.playerId == id
                override fun closeFor(playerId: PlayerId) = Unit
            }, priority = 100)
            intent("lobby.vault.open")
            assertThat(action()).isEqualTo("queue")
            val before = sim.services.getOrThrow<ICurrencyLedger>().balance(id, Currency.GOLD)
            val pad = sim.services.getOrThrow<LobbyLanePadService>().pads().values.first()
            learner.moveTo(pad)
            assertThat(action()).isNull()
            assertThat(states.find(id)?.completed).isTrue()
            assertThat(eventsOf<TutorialCompleted>().filter { it.playerId == id }).hasSize(1)
            assertThat(sim.services.getOrThrow<ICurrencyLedger>().balance(id, Currency.GOLD)).isEqualTo(before + 50)
            learner.moveTo(Vec3(pad.x + 5, pad.y, pad.z))
            learner.moveTo(pad)
            assertThat(eventsOf<TutorialCompleted>().filter { it.playerId == id }).hasSize(1)
        }
    }

    @Test
    fun `tutorial uses a priority persistence overlay installed after Core wiring`() {
        scenario("tutorial-persistence-overlay") {
            val original = sim.services.getOrThrow<IPlayerTutorialStateRepository>()
            val overlay = com.astarworks.astera.core.application.service.tutorial.InMemoryPlayerTutorialStateRepository()
            sim.services.register<IPlayerTutorialStateRepository>(overlay, priority = 100)
            val learner = sim.spawnPlayer("learner", worldId = lobby)
            assertThat(overlay.find(learner.id)?.stepIndex).isZero()
            assertThat(original.find(learner.id)).isNull()
            learner.heldItem = null // F is a lobby key only with an empty hand / the compass.
            assertThat(sim.swapHands(learner)).isTrue()
            assertThat(overlay.find(learner.id)?.stepIndex).isEqualTo(1)
            assertThat(original.find(learner.id)).isNull()
        }
    }

    @Test
    fun `game backends never enroll or present the tutorial`() {
        val sim = GameSim.boot("tutorial-game-role", role = ServerRole.GAME)
        try {
            val player = sim.spawnPlayer("learner", worldId = lobby)
            val runner = sim.services.getOrThrow<ITutorialSequencerService>()
            assertThat(runner.startSequence(player.id, TutorialScriptId("getting-started")).isFailure).isTrue()
            assertThat(sim.services.getOrThrow<IPlayerTutorialStateRepository>().find(player.id)).isNull()
            assertThat(sim.services.get<TutorialGuide>()).isNull()
        } finally { sim.shutdown() }
    }

    @Test
    fun `match entry suspends tutorial signals and presentation until the player returns`() {
        scenario("tutorial-match-suspension") {
            val learner = sim.spawnPlayer("learner", worldId = lobby)
            val states = sim.services.getOrThrow<IPlayerTutorialStateRepository>()
            val before = states.find(learner.id)
            val match = startMatch("annihilation", mapOf(learner to "red"), stage = "annihilation-arena")
            val view = checkNotNull(sim.server.view(learner.id))
            val hints = view.actionBars.size
            advance(30)
            assertThat(sim.swapHands(learner)).isFalse()
            assertThat(states.find(learner.id)).isEqualTo(before)
            assertThat(view.actionBars.drop(hints).none { it.value.startsWith("astera.tutorial.guide.") }).isTrue()
            sim.services.getOrThrow<com.astarworks.astera.match.application.port.inbound.IMatchLifecycle>()
                .end(match, com.astarworks.astera.core.domain.event.EndReason.AdminEnded)
            learner.world = lobby
            learner.moveTo(Vec3(0.5, 65.0, 6.5))
            advance(20)
            assertThat(view.actionBars.last()).isEqualTo(TutorialGuide.key("menu", "hint"))
            learner.heldItem = null // F is a lobby key only with an empty hand / the compass.
            assertThat(sim.swapHands(learner)).isTrue()
            assertThat(states.find(learner.id)?.stepIndex).isEqualTo(1)
        }
    }
}
