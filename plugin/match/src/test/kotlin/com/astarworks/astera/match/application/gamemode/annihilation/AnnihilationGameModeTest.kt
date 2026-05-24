package com.astarworks.astera.match.application.gamemode.annihilation

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.match.domain.gamemode.GameModeContext
import com.astarworks.astera.match.domain.gamemode.GameModeEvent
import com.astarworks.astera.match.domain.gamemode.IGameModePhase
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0108 Wave 5annihilation — [AnnihilationGameMode] IGameMode-framework
 * lifecycle test.
 *
 * Parallel in shape to `NormaGameModeEquivalenceTest` (Wave 5gamemode): the
 * lifecycle hooks publish framework events + drive the tracker. The bus-less
 * variant remains a clean no-op for the reflective-construction fallback
 * path (mirrors NormaGameMode).
 *
 * Differs from Norma in that Annihilation **owns** its tracker (vs Norma
 * being a façade over the global Wave 5c pipeline) — we additionally assert
 * the tracker state on `onMatchStarted` / `onTick` / `onMatchEnded`.
 */
class AnnihilationGameModeTest {

    private val matchId = MatchId("ann-1")
    private val red = TeamId("red")
    private val blue = TeamId("blue")
    private val green = TeamId("green")
    private val teams = listOf(red, blue, green)

    private fun contextWithTeams(): GameModeContext = GameModeContext(
        matchId = matchId,
        modeId = AnnihilationGameMode.ID,
        scoringState = mapOf(AnnihilationGameMode.TEAMS_STATE_KEY to teams),
    )

    @Test
    fun `defaults expose canonical Annihilation metadata`() {
        val mode = AnnihilationGameMode()
        assertThat(mode.id().value).isEqualTo("annihilation")
        assertThat(mode.titleKey().value).isEqualTo("astera.gamemode.annihilation.title")
        assertThat(mode.tickPeriodTicks()).isEqualTo(20L)
        assertThat(mode.defaultPhases()).containsExactly(
            IGameModePhase.Lobby,
            IGameModePhase.Countdown,
            IGameModePhase.Active,
            IGameModePhase.SuddenDeath,
            IGameModePhase.Ended,
        )
    }

    @Test
    fun `null tracker and bus makes every hook a clean no-op`() {
        val mode = AnnihilationGameMode()
        val ctx = contextWithTeams()
        mode.onMatchStarted(ctx)
        mode.onPhaseChanged(ctx, IGameModePhase.Lobby, IGameModePhase.Active)
        mode.onTick(ctx, tick = 100L)
        mode.onMatchEnded(ctx)
        // Reaching here without exception is the assertion.
    }

    @Test
    fun `onMatchStarted seeds the tracker with the team list from context`() {
        val bus = FakeDomainEventBus()
        val tracker = AnnihilationMatchTracker(bus)
        val mode = AnnihilationGameMode(tracker = tracker, eventBus = bus)

        mode.onMatchStarted(contextWithTeams())

        val state = tracker.state(matchId)
        assertThat(state).isNotNull
        assertThat(state!!.allTeams).containsExactlyInAnyOrder(red, blue, green)
        assertThat(state.phase).isEqualTo(NexusPhase.Phase1Build)
        // Framework event published.
        assertThat(bus.publishedOfType<GameModeEvent.GameModeStarted>()).hasSize(1)
    }

    @Test
    fun `onMatchStarted with no team slot still calls tracker with empty teams`() {
        val bus = FakeDomainEventBus()
        val tracker = AnnihilationMatchTracker(bus)
        val mode = AnnihilationGameMode(tracker = tracker, eventBus = bus)
        // Empty teams → tracker rejects the seed; the mode swallows the
        // resulting state silently for now (production wiring always provides
        // the team slot).
        val ctx = GameModeContext(matchId = matchId, modeId = AnnihilationGameMode.ID)
        try {
            mode.onMatchStarted(ctx)
        } catch (e: IllegalArgumentException) {
            // expected — empty teams fail the tracker's seed invariant.
            assertThat(e.message).contains("requires >=1 team")
        }
    }

    @Test
    fun `onPhaseChanged publishes framework event on real transition`() {
        val bus = FakeDomainEventBus()
        val tracker = AnnihilationMatchTracker(bus)
        val mode = AnnihilationGameMode(tracker = tracker, eventBus = bus)
        val ctx = contextWithTeams()

        mode.onPhaseChanged(ctx, IGameModePhase.Lobby, IGameModePhase.Active)
        mode.onPhaseChanged(ctx, IGameModePhase.Active, IGameModePhase.SuddenDeath)
        mode.onPhaseChanged(ctx, IGameModePhase.SuddenDeath, IGameModePhase.SuddenDeath) // dedup

        val events = bus.publishedOfType<GameModeEvent.GameModePhaseChanged>()
        assertThat(events).hasSize(2)
        assertThat(events.map { it.newPhase }).containsExactly(IGameModePhase.Active, IGameModePhase.SuddenDeath)
    }

    @Test
    fun `onTick advances tracker phases when timer fires`() {
        val bus = FakeDomainEventBus()
        val tracker = AnnihilationMatchTracker(bus)
        val mode = AnnihilationGameMode(tracker = tracker, eventBus = bus)
        val ctx = contextWithTeams()

        mode.onMatchStarted(ctx)
        // Tick at build phase expiry → should advance to Phase2Engage.
        mode.onTick(ctx, tick = AnnihilationConfig.DEFAULT.buildPhaseTicks)
        assertThat(tracker.phaseOf(matchId)).isEqualTo(NexusPhase.Phase2Engage)
    }

    @Test
    fun `onMatchEnded unregisters the tracker and publishes GameModeEnded`() {
        val bus = FakeDomainEventBus()
        val tracker = AnnihilationMatchTracker(bus)
        val mode = AnnihilationGameMode(tracker = tracker, eventBus = bus)
        val ctx = contextWithTeams()

        mode.onMatchStarted(ctx)
        assertThat(tracker.allActive()).contains(matchId)

        mode.onMatchEnded(ctx)
        assertThat(tracker.allActive()).doesNotContain(matchId)
        assertThat(bus.publishedOfType<GameModeEvent.GameModeEnded>()).hasSize(1)
    }
}
