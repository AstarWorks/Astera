package com.astarworks.astera.core.application.service.tutorial

import com.astarworks.astera.core.application.contents.TutorialContents
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IDialogPredicateEvaluator
import com.astarworks.astera.core.application.port.outbound.lobby.LobbyTrainingRange
import com.astarworks.astera.core.application.usecase.tutorial.RunTutorialSequencerUseCase
import com.astarworks.astera.core.domain.dialog.DialogPredicate
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hologram.HologramVisibilityScope
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.lobby.LobbyTrainingLayout
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.*
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class TutorialGuideTest {
    @Test
    fun `HUD observation resets outside lobby and private markers are removed on quit and shutdown`() {
        val player = FakeMcPlayer(PlayerId(UUID.randomUUID()))
        val server = FakeMcServer().apply { register(player) }
        val clock = FakeScheduler()
        val states = InMemoryPlayerTutorialStateRepository()
        val registry = TutorialContents()
        val script = TutorialScriptId("getting-started")
        registry.register(TutorialScript(script, MessageKey("astera.tutorial.welcome"), listOf(
            TutorialStep.WaitForAction("hud"), TutorialStep.WaitForAction("shop"),
        )))
        val evaluator = object : IDialogPredicateEvaluator {
            override fun evaluate(playerId: PlayerId, predicate: DialogPredicate) = true
        }
        val runner = RunTutorialSequencerUseCase(registry, states, InMemoryDomainEventBus(), clock, evaluator)
        runner.startSequence(player.id, script)
        val markers = FakeHologramGateway()
        var eligible = true
        val guide = TutorialGuide(runner, server, clock, markers,
            range = { LobbyTrainingRange(WorldId("lobby"), LobbyTrainingLayout.plan(0, 0, 64)) },
            eligible = { eligible }, landmark = { Vec3.ZERO })
        guide.joined(player.id)
        guide.tick()
        assertThat(player.titles.single().titleKey).isEqualTo(TutorialGuide.key("hud", "title"))
        assertThat(player.actionBars.last().key).isEqualTo(TutorialGuide.key("hud", "hint"))
        val marker = checkNotNull(markers.snapshot(markers.all().single()))
        assertThat(marker.visibilityScope).isEqualTo(HologramVisibilityScope.PerPlayer(setOf(player.id)))
        clock.advanceTicks(60)
        guide.tick()
        assertThat(states.find(player.id)?.stepIndex).isZero()
        eligible = false
        guide.tick()
        assertThat(markers.all()).isEmpty()
        clock.advanceTicks(100)
        eligible = true
        guide.tick()
        clock.advanceTicks(79)
        guide.tick()
        assertThat(states.find(player.id)?.stepIndex).isZero()
        clock.advanceTicks(1)
        guide.tick()
        assertThat(states.find(player.id)?.stepIndex).isEqualTo(1)
        guide.tick()
        guide.left(player.id)
        assertThat(markers.all()).isEmpty()
        guide.joined(player.id)
        guide.tick()
        assertThat(markers.all()).hasSize(1)
        guide.close()
        assertThat(markers.all()).isEmpty()
    }
}
