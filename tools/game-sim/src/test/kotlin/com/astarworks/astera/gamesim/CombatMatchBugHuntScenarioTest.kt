package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CombatMatchBugHuntScenarioTest {
    @Test
    fun `players can enter a second match after the first ends`() {
        scenario("bug-hunt-second-match") {
            val red = player("red")
            val blue = player("blue")
            val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
            val matches = sim.services.getOrThrow<IMatchRepository>()
            val first = startMatch("norma", mapOf(red to "red", blue to "blue"))
            lifecycle.end(first, EndReason.AdminEnded)
            assertThat(matches.findMatchOf(red.id)).isNull()
            val second = startMatch("norma", mapOf(red to "blue", blue to "red"))
            assertThat(matches.findMatchOf(red.id)?.id).isEqualTo(second)
            assertThat(matches.findMatchOf(blue.id)?.id).isEqualTo(second)
        }
    }
    @Test
    fun `assist attribution is scoped to one life through production wiring`() {
        scenario("bug-hunt-assist-lives") {
            val helper = player("helper")
            val killer = player("killer")
            val victim = player("victim")
            val history = sim.services.getOrThrow<com.astarworks.astera.combat.application.port.outbound.IDamageHistoryService>()
            history.record(victim.id, com.astarworks.astera.combat.domain.damage.DamageEntry(helper.id, 5.0, sim.tick))
            val death = com.astarworks.astera.combat.domain.event.PlayerKilled(
                victim.id, killer.id, com.astarworks.astera.combat.domain.damage.DamageSource.Environment.Generic, sim.tick,
            )
            sim.eventBus.publish(death)
            sim.eventBus.publish(death.copy(atTick = sim.tick + 1))
            assertThat(eventsOf<com.astarworks.astera.combat.domain.event.AssistCredited>().map { it.player })
                .containsExactly(helper.id)
        }
    }
    @Test
    fun `nova does not damage a mob at the same coordinates in another world`() {
        scenario("bug-hunt-aoe-world") {
            val caster = player("caster")
            val local = mob("local", caster.position)
            val remote = mob("remote", caster.position)
            remote.world = com.astarworks.astera.core.domain.world.WorldId("other-world")
            caster.cast("frost-nova")
            advance(1)
            assertThat(eventsOf<com.astarworks.astera.combat.domain.event.DamageDealt>().map { it.target })
                .contains(local.id).doesNotContain(remote.id)
        }
    }
    @Test
    fun `production recorder captures movement on each tick`() {
        scenario("bug-hunt-replay-ticks") {
            val red = player("red")
            val blue = player("blue")
            val id = startMatch("norma", mapOf(red to "red", blue to "blue"))
            val recorder = sim.services.getOrThrow<com.astarworks.astera.match.application.port.inbound.IMatchRecorder>()
            recorder.startRecording(id, com.astarworks.astera.core.domain.replay.ReplayFormat.FullPerTick(100))
            val started = sim.tick
            repeat(5) { advance(1) }
            val stopped = recorder.stopRecording(id)
            check(stopped is com.astarworks.astera.core.domain.Result.Success)
            assertThat(stopped.value.keyFrames.map { it.tick }).containsExactlyElementsOf((started + 1..started + 5).toList())
        }
    }
    @Test
    fun `natural sudden death expiry runs match end cleanup`() {
        scenario("bug-hunt-natural-end") {
            val red = player("red")
            val blue = player("blue")
            val id = startMatch("norma", mapOf(red to "red", blue to "blue"))
            val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
            lifecycle.switchPhase(id, com.astarworks.astera.match.domain.match.MatchStateMachine.TransitionTrigger.ScoreThresholdReached)
            advance(com.astarworks.astera.match.domain.match.PhaseTimings.SUDDEN_DEATH_TICKS.toInt() + 40)
            assertThat(eventsOf<com.astarworks.astera.core.domain.event.MatchEnded>().map { it.matchId }).containsExactly(id)
            assertThat(sim.services.getOrThrow<com.astarworks.astera.match.application.port.outbound.IGameModeScheduler>().activeMatches())
                .doesNotContain(id)
        }
    }
}
