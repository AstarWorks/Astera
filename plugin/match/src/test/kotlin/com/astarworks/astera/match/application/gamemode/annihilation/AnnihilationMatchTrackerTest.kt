package com.astarworks.astera.match.application.gamemode.annihilation

import com.astarworks.astera.core.domain.event.AnnihilationPhaseTransitioned
import com.astarworks.astera.core.domain.event.NexusDamaged
import com.astarworks.astera.core.domain.event.NexusDestroyed
import com.astarworks.astera.core.domain.event.TeamEliminated
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.NexusDamageOutcome
import com.astarworks.astera.core.domain.nexus.NexusId
import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import com.astarworks.astera.match.domain.gamemode.annihilation.WinConditionRules
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * ADR-0108 Wave 5annihilation — tracker tests covering damage emission,
 * phase transition events, and team elimination dispatch.
 */
class AnnihilationMatchTrackerTest {

    private val matchId = MatchId("track-1")
    private val red = TeamId("red")
    private val blue = TeamId("blue")
    private val green = TeamId("green")
    private val attacker = PlayerId(UUID.randomUUID())
    private val config = AnnihilationConfig.DEFAULT

    private fun freshTracker(): Pair<AnnihilationMatchTracker, FakeDomainEventBus> {
        val bus = FakeDomainEventBus()
        val tracker = AnnihilationMatchTracker(bus)
        tracker.register(matchId, config, listOf(red, blue, green))
        return tracker to bus
    }

    @Test
    fun `recordDamage on BUILDING pillar publishes nothing`() {
        val (tracker, bus) = freshTracker()
        val outcome = tracker.recordDamage(matchId, NexusId("track-1-red-1"), 100, attacker, atTick = 10L)
        assertThat(outcome).isInstanceOf(NexusDamageOutcome.Ignored::class.java)
        assertThat(bus.published).isEmpty()
    }

    @Test
    fun `recordDamage on VULNERABLE non-lethal publishes NexusDamaged only`() {
        val (tracker, bus) = freshTracker()
        tracker.advancePhasesIfNeeded(matchId, currentTick = config.buildPhaseTicks)
        // Now Phase 2 — Tier 1 vulnerable.
        bus.published.clear()

        val outcome = tracker.recordDamage(
            matchId, NexusId("track-1-red-1"), amount = 50, attackerId = attacker, atTick = 7000L,
        )
        assertThat(outcome).isInstanceOf(NexusDamageOutcome.Damaged::class.java)
        val damaged = bus.publishedOfType<NexusDamaged>()
        assertThat(damaged).hasSize(1)
        assertThat(damaged[0].nexusId.value).isEqualTo("track-1-red-1")
        assertThat(damaged[0].owningTeam).isEqualTo(red)
        assertThat(damaged[0].tier).isEqualTo(NexusPillarTier.TIER_1)
        assertThat(damaged[0].damageAmount).isEqualTo(50)
        assertThat(damaged[0].previousHp).isEqualTo(config.pillarHp)
        assertThat(damaged[0].currentHp).isEqualTo(config.pillarHp - 50)
        assertThat(damaged[0].attackerId).isEqualTo(attacker)
        assertThat(bus.publishedOfType<NexusDestroyed>()).isEmpty()
    }

    @Test
    fun `recordDamage lethal on Tier 1 publishes NexusDestroyed without TeamEliminated`() {
        val (tracker, bus) = freshTracker()
        tracker.advancePhasesIfNeeded(matchId, currentTick = config.buildPhaseTicks)
        bus.published.clear()

        tracker.recordDamage(
            matchId, NexusId("track-1-red-1"), amount = config.pillarHp, attackerId = attacker, atTick = 7000L,
        )
        assertThat(bus.publishedOfType<NexusDestroyed>()).hasSize(1)
        assertThat(bus.publishedOfType<TeamEliminated>()).isEmpty()
    }

    @Test
    fun `recordDamage lethal on Tier 3 publishes NexusDestroyed AND TeamEliminated`() {
        val (tracker, bus) = freshTracker()
        // Advance to Phase 3 (engage timer expires).
        tracker.advancePhasesIfNeeded(matchId, currentTick = config.buildPhaseTicks)
        tracker.advancePhasesIfNeeded(matchId, currentTick = config.buildPhaseTicks + config.engagePhaseTicks)
        assertThat(tracker.phaseOf(matchId)).isEqualTo(NexusPhase.Phase3Tier3)
        bus.published.clear()

        tracker.recordDamage(
            matchId, NexusId("track-1-red-3"), amount = config.pillarHp, attackerId = attacker, atTick = 30_000L,
        )
        assertThat(bus.publishedOfType<NexusDestroyed>()).hasSize(1)
        val elim = bus.publishedOfType<TeamEliminated>()
        assertThat(elim).hasSize(1)
        assertThat(elim[0].teamId).isEqualTo(red)
        assertThat(elim[0].matchId).isEqualTo(matchId)
    }

    @Test
    fun `advancePhasesIfNeeded fires AnnihilationPhaseTransitioned on advance`() {
        val (tracker, bus) = freshTracker()
        val next = tracker.advancePhasesIfNeeded(matchId, currentTick = config.buildPhaseTicks)
        assertThat(next).isEqualTo(NexusPhase.Phase2Engage)
        val events = bus.publishedOfType<AnnihilationPhaseTransitioned>()
        assertThat(events).hasSize(1)
        assertThat(events[0].oldPhase).isEqualTo(NexusPhase.Phase1Build)
        assertThat(events[0].newPhase).isEqualTo(NexusPhase.Phase2Engage)
    }

    @Test
    fun `advancePhasesIfNeeded returns null and emits nothing before timer`() {
        val (tracker, bus) = freshTracker()
        val next = tracker.advancePhasesIfNeeded(matchId, currentTick = 100L)
        assertThat(next).isNull()
        assertThat(bus.published).isEmpty()
    }

    @Test
    fun `Phase2 entry opens Tier 1 pillars for combat`() {
        val (tracker, _) = freshTracker()
        tracker.advancePhasesIfNeeded(matchId, currentTick = config.buildPhaseTicks)
        val state = tracker.state(matchId)!!
        val tier1s = state.pillars.values.filter { it.tier == NexusPillarTier.TIER_1 }
        tier1s.forEach { assertThat(it.isVulnerable).isTrue() }
    }

    @Test
    fun `Phase3 entry opens Tier 2 and Tier 3 pillars for combat`() {
        val (tracker, _) = freshTracker()
        tracker.advancePhasesIfNeeded(matchId, currentTick = config.buildPhaseTicks)
        tracker.advancePhasesIfNeeded(matchId, currentTick = config.buildPhaseTicks + config.engagePhaseTicks)
        val state = tracker.state(matchId)!!
        state.pillars.values
            .filter { it.tier != NexusPillarTier.TIER_1 }
            .forEach { assertThat(it.isVulnerable).isTrue() }
    }

    @Test
    fun `applySuddenDeathTick is no-op outside Phase 4`() {
        val (tracker, bus) = freshTracker()
        val results = tracker.applySuddenDeathTick(matchId, currentTick = 10L)
        assertThat(results).isEmpty()
        assertThat(bus.published).isEmpty()
    }

    @Test
    fun `applySuddenDeathTick damages every surviving Tier 3 in Phase 4`() {
        val (tracker, bus) = freshTracker()
        // Force into Phase 4 (advance through all timers).
        var tick = 0L
        tick += config.buildPhaseTicks; tracker.advancePhasesIfNeeded(matchId, tick)
        tick += config.engagePhaseTicks; tracker.advancePhasesIfNeeded(matchId, tick)
        tick += config.tier3PhaseTicks; tracker.advancePhasesIfNeeded(matchId, tick)
        assertThat(tracker.phaseOf(matchId)).isEqualTo(NexusPhase.Phase4SuddenDeath)
        bus.published.clear()

        val results = tracker.applySuddenDeathTick(matchId, currentTick = tick + 1L)
        assertThat(results).hasSize(3)
        assertThat(bus.publishedOfType<NexusDamaged>()).hasSize(3)
    }

    @Test
    fun `evaluateWinCondition returns InProgress mid-game`() {
        val (tracker, _) = freshTracker()
        tracker.advancePhasesIfNeeded(matchId, currentTick = config.buildPhaseTicks)
        assertThat(tracker.evaluateWinCondition(matchId, currentTick = 7000L))
            .isEqualTo(WinConditionRules.Outcome.InProgress)
    }

    @Test
    fun `evaluateWinCondition returns Winner after last enemy team eliminated`() {
        val (tracker, _) = freshTracker()
        tracker.advancePhasesIfNeeded(matchId, currentTick = config.buildPhaseTicks)
        tracker.advancePhasesIfNeeded(matchId, currentTick = config.buildPhaseTicks + config.engagePhaseTicks)
        tracker.recordDamage(matchId, NexusId("track-1-red-3"), config.pillarHp, attacker, atTick = 30_000L)
        tracker.recordDamage(matchId, NexusId("track-1-blue-3"), config.pillarHp, attacker, atTick = 30_100L)
        val outcome = tracker.evaluateWinCondition(matchId, currentTick = 30_200L)
        assertThat(outcome).isEqualTo(WinConditionRules.Outcome.Winner(green))
    }

    @Test
    fun `unregister removes the match`() {
        val (tracker, _) = freshTracker()
        assertThat(tracker.allActive()).contains(matchId)
        tracker.unregister(matchId)
        assertThat(tracker.allActive()).doesNotContain(matchId)
        assertThat(tracker.state(matchId)).isNull()
    }

    @Test
    fun `recordDamage on unknown match returns Ignored`() {
        val bus = FakeDomainEventBus()
        val tracker = AnnihilationMatchTracker(bus)
        val outcome = tracker.recordDamage(
            MatchId("nope"), NexusId("x-y-1"), 10, attacker, atTick = 0L,
        )
        assertThat(outcome).isInstanceOf(NexusDamageOutcome.Ignored::class.java)
        assertThat(bus.published).isEmpty()
    }

    @Test
    fun `scoresOf returns leader-first ordering`() {
        val (tracker, _) = freshTracker()
        tracker.advancePhasesIfNeeded(matchId, currentTick = config.buildPhaseTicks)
        // Damage red so its score drops.
        tracker.recordDamage(matchId, NexusId("track-1-red-1"), 200, attacker, atTick = 7000L)
        val scores = tracker.scoresOf(matchId)
        assertThat(scores).hasSize(3)
        assertThat(scores[0].team).isIn(blue, green)
        assertThat(scores.last().team).isEqualTo(red)
    }
}
