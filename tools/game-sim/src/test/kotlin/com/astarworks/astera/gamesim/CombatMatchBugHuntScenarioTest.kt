package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.port.outbound.IDamageHistoryService
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageEntry
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.AssistCredited
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.replay.ReplayFormat
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.inbound.IMatchRecorder
import com.astarworks.astera.match.application.port.outbound.IGameModeScheduler
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.match.domain.match.PhaseTimings
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
            val first = startMatch("skirmish", mapOf(red to "red", blue to "blue"))
            lifecycle.end(first, EndReason.AdminEnded)
            assertThat(matches.findMatchOf(red.id)).isNull()
            val second = startMatch("skirmish", mapOf(red to "blue", blue to "red"))
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
            val history = sim.services.getOrThrow<IDamageHistoryService>()
            history.record(victim.id, DamageEntry(helper.id, 5.0, sim.tick))
            val death = PlayerKilled(
                victim.id, killer.id, DamageSource.Environment.Generic, sim.tick,
            )
            sim.eventBus.publish(death)
            sim.eventBus.publish(death.copy(atTick = sim.tick + 1))
            assertThat(eventsOf<AssistCredited>().map { it.player })
                .containsExactly(helper.id)
        }
    }

    @Test
    fun `nova does not damage a mob at the same coordinates in another world`() {
        scenario("bug-hunt-aoe-world") {
            val caster = player("caster")
            val local = mob("local", caster.position)
            val remote = mob("remote", caster.position)
            remote.world = WorldId("other-world")
            caster.cast("frost-nova")
            advance(1)
            assertThat(eventsOf<DamageDealt>().map { it.target })
                .contains(local.id).doesNotContain(remote.id)
        }
    }

    @Test
    fun `production recorder captures frames on each tick`() {
        scenario("bug-hunt-replay-ticks") {
            val red = player("red")
            val blue = player("blue")
            val id = startMatch("skirmish", mapOf(red to "red", blue to "blue"))
            val recorder = sim.services.getOrThrow<IMatchRecorder>()
            recorder.startRecording(id, ReplayFormat.FullPerTick(100))
            val started = sim.tick
            repeat(5) { advance(1) }
            val stopped = recorder.stopRecording(id)
            check(stopped is Result.Success)
            assertThat(stopped.value.keyFrames.map { it.tick }).containsExactlyElementsOf((started + 1..started + 5).toList())
        }
    }

    @Test
    fun `natural sudden death expiry runs match end cleanup`() {
        scenario("bug-hunt-natural-end") {
            val red = player("red")
            val blue = player("blue")
            val id = startMatch("skirmish", mapOf(red to "red", blue to "blue"))
            val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
            lifecycle.switchPhase(id, MatchStateMachine.TransitionTrigger.ScoreThresholdReached)
            advance(PhaseTimings.SUDDEN_DEATH_TICKS.toInt() + 40)
            assertThat(eventsOf<MatchEnded>().map { it.matchId }).containsExactly(id)
            assertThat(sim.services.getOrThrow<IGameModeScheduler>().activeMatches())
                .doesNotContain(id)
        }
    }

    @Test
    fun `friendly fire uses match teams registered after combat enables`() {
        scenario("bug-hunt-team-wiring", standaloneTeams = false) {
            val red = player("red", team = "red")
            val mate = player("mate", team = "blue")
            val enemy = player("enemy", team = "red")
            startMatch("skirmish", mapOf(red to "red", mate to "red", enemy to "blue"))
            val damage = checkNotNull(sim.combat.applyDamage)
            val source = DamageSource.Skill(
                SkillId("frost-nova"), red.id,
            )
            val profile = DamageProfile.single(
                1.0, DamageAttribute.PHYSICAL,
            )
            assertThat(damage.execute(DamageAttempt(mate.id, profile, source)))
                .isInstanceOf(Result.Failure::class.java)
            assertThat(damage.execute(DamageAttempt(enemy.id, profile, source)))
                .isInstanceOf(Result.Success::class.java)
        }
    }

    @Test
    fun `match shutdown cancels phase and game mode tickers`() {
        scenario("bug-hunt-match-shutdown") {
            val red = player("red")
            val blue = player("blue")
            val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
            val started = lifecycle.start(StageId("siege-isles"), "skirmish")
            check(started is Result.Success)
            lifecycle.entry(started.value, red.id, TeamId("red"))
            lifecycle.entry(started.value, blue.id, TeamId("blue"))
            val modes = sim.services.getOrThrow<IGameModeScheduler>()
            assertThat(modes.activeMatches()).contains(started.value)
            sim.match.shutdown()
            sim.scheduler.advanceTicks(1000)
            assertThat(sim.match.matches.findById(started.value)?.phase)
                .isEqualTo(MatchPhase.COUNTDOWN)
            assertThat(modes.activeMatches()).isEmpty()
        }
    }

    @Test
    fun `aborting countdown frees the stage for the next match`() {
        scenario("bug-hunt-abort-countdown") {
            val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
            val stage = StageId("siege-isles")
            val first = lifecycle.start(stage)
            check(first is Result.Success)
            lifecycle.end(first.value, EndReason.Cancelled("admin-abort"))
            assertThat(sim.match.matches.findActiveMatches()).isEmpty()
            assertThat(lifecycle.start(stage)).isInstanceOf(Result.Success::class.java)
        }
    }

    @Test
    fun `status application uses the shipped poison duration`() {
        scenario("bug-hunt-status-duration") {
            val target = player("target")
            val applied = checkNotNull(sim.combat.applyStatus).execute(
                target.id, StatusEffectId("poison"),
            )
            check(applied is Result.Success)
            assertThat(applied.value.remainingTicks).isEqualTo(200)
        }
    }
}
