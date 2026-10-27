package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.inbound.ITutorialSequencerService
import com.astarworks.astera.core.application.port.outbound.*
import com.astarworks.astera.core.application.port.outbound.lobby.ILobbyTrainingRange
import com.astarworks.astera.core.application.service.lobby.LobbyLanePadService
import com.astarworks.astera.core.application.service.tutorial.TutorialGuide
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.event.PlayerActiveWeaponChanged
import com.astarworks.astera.core.domain.event.TutorialCompleted
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOfferId
import com.astarworks.astera.core.domain.proxy.ServerRole
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import com.astarworks.astera.core.domain.tutorial.TutorialStep
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TutorialScenarioTest {
    private val lobby = WorldId("lobby")

    @Test
    fun `first join walks the seven progress steps, resumes after skip and reconnect, and graduates once`() {
        scenario("new-player-guided-tutorial") {
            sim.trainingShells.worldLoaded = true
            var learner = sim.spawnPlayer("learner", at = Vec3(0.5, 65.0, 6.5), worldId = lobby)
            val runner = sim.services.getOrThrow<ITutorialSequencerService>()
            val states = sim.services.getOrThrow<IPlayerTutorialStateRepository>()
            val holograms = sim.services.getOrThrow<IHologramGateway>()
            val router = sim.services.getOrThrow<IGuiIntentRouter>()
            fun action(): String? = (runner.currentStep(learner.id) as? TutorialStep.WaitForAction)?.actionId
            fun titles() = checkNotNull(sim.server.view(learner.id)).titles
            assertThat(action()).isEqualTo("menu") // CreateFirstTimeProfile started it, no direct sequencer start.
            advance(10)
            assertThat(titles()).contains(TutorialGuide.key("menu", "title"))
            val bubbleOnly = holograms.all().size // menu step: speech bubble, no arrow.
            learner.heldItem = null // F is a lobby key only with an empty hand / the compass.
            assertThat(sim.swapHands(learner)).isTrue() // F -> real kit entry -> rendered menu.
            assertThat(action()).isEqualTo("queue")
            advance(100)
            assertThat(action()).isEqualTo("queue") // waiting is never enough.
            assertThat(titles()).contains(TutorialGuide.key("done", "title")) // completion feedback.
            assertThat(holograms.all().size).isEqualTo(bubbleOnly + 1) // queue step: bubble + arrow over the Play guide.
            val reconnectId = learner.id
            sim.detachPlayer(reconnectId)
            learner = sim.attachPlayer(reconnectId, "learner").also { it.world = lobby }
            advance(10)
            assertThat(action()).isEqualTo("queue") // resumes where the player left off.
            assertThat(titles()).contains(TutorialGuide.key("queue", "title"))

            assertThat(sim.command("learner", "tutorial skip").succeeded).isTrue()
            assertThat(states.find(learner.id)?.paused).isTrue()
            assertThat(holograms.all().size).isEqualTo(bubbleOnly - 1) // hologram assist is removed on skip.
            val id = learner.id
            sim.detachPlayer(id)
            learner = sim.attachPlayer(id, "learner").also { it.world = lobby; it.position = Vec3(0.5, 65.0, 6.5) }
            advance(10)
            assertThat(runner.currentStep(id)).isNull() // skip survives a reconnect.
            assertThat(sim.command("learner", "tutorial status").succeeded).isTrue()
            assertThat(sim.command("learner", "tutorial start").succeeded).isTrue()
            assertThat(action()).isEqualTo("queue")

            val before = sim.services.getOrThrow<ICurrencyLedger>().balance(id, Currency.GOLD)
            val pad = sim.services.getOrThrow<LobbyLanePadService>().pads().values.first()
            learner.moveTo(pad)
            assertThat(action()).isEqualTo("match")
            val match = startMatch("annihilation", mapOf(learner to "red"), stage = "annihilation-arena")
            advance(20)
            assertThat(action()).isEqualTo("match") // a match in progress never advances the tutorial.
            sim.services.getOrThrow<com.astarworks.astera.match.application.port.inbound.IMatchLifecycle>()
                .end(match, com.astarworks.astera.core.domain.event.EndReason.AdminEnded)
            learner.world = lobby
            learner.moveTo(Vec3(0.5, 65.0, 6.5))
            advance(40)
            assertThat(action()).isEqualTo("result")
            val view = checkNotNull(sim.server.view(id))
            router.dispatch(view, GuiIntent("stats.last"))
            assertThat(action()).isEqualTo("shop")
            sim.eventBus.publish(CurrencyChanged(id, Currency.GOLD, -20L, 30L, CurrencyChangeReason.ShopPurchase(ShopId("market"), ShopOfferId("test-offer"))))
            assertThat(action()).isEqualTo("equip")
            sim.eventBus.publish(PlayerActiveWeaponChanged(id, null, ItemId("knight-sword"), 0L))
            assertThat(action()).isEqualTo("missions")
            router.dispatch(view, GuiIntent("progress.open"))
            assertThat(action()).isNull()
            assertThat(states.find(id)?.completed).isTrue()
            assertThat(eventsOf<TutorialCompleted>().filter { it.playerId == id }).hasSize(1)
            assertThat(sim.services.getOrThrow<ICurrencyLedger>().balance(id, Currency.GOLD)).isGreaterThan(before - 1)
            assertThat(holograms.all().size).isEqualTo(bubbleOnly - 1) // no assist left after the last step.
            learner.moveTo(pad)
            assertThat(eventsOf<TutorialCompleted>().filter { it.playerId == id }).hasSize(1)
        }
    }

    @Test
    fun `lobby sidebar carries the next tutorial step line`() {
        scenario("tutorial-next-step-sidebar") {
            val learner = sim.spawnPlayer("learner", worldId = lobby)
            advance(40)
            val keys = checkNotNull(sim.hud.sidebar(learner.id)).lines.map { (it as com.astarworks.astera.core.domain.hud.HudComponent.Text).messageKey.value }
            assertThat(keys).contains(TutorialGuide.key("menu", "next").value)
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
