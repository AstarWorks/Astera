package com.astarworks.astera.match.application.gamemode

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.gamemode.GameModeContext
import com.astarworks.astera.match.domain.gamemode.GameModeEvent
import com.astarworks.astera.match.domain.gamemode.IGameModePhase
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0106 Wave 5gamemode — equivalence test for [NormaGameMode].
 *
 * The Norma framework wrapper MUST NOT change the Wave 5c pipeline behaviour
 * (Star Generator + Anomaly + Sudden Death schedulers + BreakCoreListener
 * remain globally wired in `AsteraMatchPlugin.onEnable`). NormaGameMode's
 * lifecycle hooks publish framework-level [GameModeEvent]s and otherwise
 * leave the world alone.
 *
 * "Equivalence" here means:
 *
 *   1. `onTick` is a no-op (does not publish events, does not invoke any
 *      collaborator). The Wave 5c global StarGeneratorScheduler /
 *      AnomalyScheduler do the actual work.
 *   2. `onMatchStarted` / `onMatchEnded` publish one framework event each
 *      (GameModeStarted / GameModeEnded). These are *additive* to the
 *      existing `MatchStarted` / `MatchEnded` core events the match state
 *      machine already publishes.
 *   3. `onPhaseChanged` publishes one GameModePhaseChanged event per *new*
 *      transition; redundant calls (oldPhase == newPhase) are deduped to
 *      preserve the "framework saw a real transition" contract.
 *   4. With a null event bus (test default), every hook is a clean no-op.
 *      This is the seam test fakes use to swap out the framework without
 *      touching the rest of the wiring.
 *
 * The test does NOT exercise the global StarGeneratorScheduler /
 * AnomalyScheduler — those are wired in `AsteraMatchPlugin.onEnable` and
 * remain covered by their existing unit tests
 * (`StarGeneratorTest`, `AnomalyTypeTest`, `MatchStateMachineTest`).
 */
class NormaGameModeEquivalenceTest {

    private val matchId = MatchId("equiv-1")
    private val context = GameModeContext(matchId = matchId, modeId = NormaGameMode.ID)

    @Test
    fun `onTick is a side-effect-free no-op`() {
        val bus = FakeDomainEventBus()
        val mode = NormaGameMode(eventBus = bus)

        mode.onTick(context, tick = 100L)
        mode.onTick(context, tick = 200L)
        mode.onTick(context, tick = 300L)

        assertThat(bus.published).isEmpty()
    }

    @Test
    fun `onMatchStarted publishes exactly one GameModeStarted`() {
        val bus = FakeDomainEventBus()
        val mode = NormaGameMode(eventBus = bus)

        mode.onMatchStarted(context)

        val events = bus.publishedOfType<GameModeEvent.GameModeStarted>()
        assertThat(events).hasSize(1)
        assertThat(events[0].matchId).isEqualTo(matchId)
        assertThat(events[0].modeId).isEqualTo(NormaGameMode.ID)
    }

    @Test
    fun `onMatchEnded publishes exactly one GameModeEnded`() {
        val bus = FakeDomainEventBus()
        val mode = NormaGameMode(eventBus = bus)

        mode.onMatchEnded(context)

        val events = bus.publishedOfType<GameModeEvent.GameModeEnded>()
        assertThat(events).hasSize(1)
        assertThat(events[0].matchId).isEqualTo(matchId)
        assertThat(events[0].modeId).isEqualTo(NormaGameMode.ID)
    }

    @Test
    fun `onPhaseChanged publishes one event per real transition`() {
        val bus = FakeDomainEventBus()
        val mode = NormaGameMode(eventBus = bus)

        mode.onPhaseChanged(context, IGameModePhase.Lobby, IGameModePhase.Countdown)
        mode.onPhaseChanged(context, IGameModePhase.Countdown, IGameModePhase.Active)
        mode.onPhaseChanged(context, IGameModePhase.Active, IGameModePhase.SuddenDeath)
        mode.onPhaseChanged(context, IGameModePhase.SuddenDeath, IGameModePhase.Ended)

        val events = bus.publishedOfType<GameModeEvent.GameModePhaseChanged>()
        assertThat(events).hasSize(4)
        assertThat(events.map { it.oldPhase to it.newPhase }).containsExactly(
            IGameModePhase.Lobby to IGameModePhase.Countdown,
            IGameModePhase.Countdown to IGameModePhase.Active,
            IGameModePhase.Active to IGameModePhase.SuddenDeath,
            IGameModePhase.SuddenDeath to IGameModePhase.Ended,
        )
    }

    @Test
    fun `onPhaseChanged dedupes identical-phase calls`() {
        val bus = FakeDomainEventBus()
        val mode = NormaGameMode(eventBus = bus)

        mode.onPhaseChanged(context, IGameModePhase.Active, IGameModePhase.Active)

        assertThat(bus.publishedOfType<GameModeEvent.GameModePhaseChanged>()).isEmpty()
    }

    @Test
    fun `null event bus makes every hook a clean no-op`() {
        val mode = NormaGameMode(eventBus = null)

        mode.onMatchStarted(context)
        mode.onPhaseChanged(context, IGameModePhase.Lobby, IGameModePhase.Active)
        mode.onTick(context, tick = 1L)
        mode.onMatchEnded(context)
        // Reaching here without exception is the assertion.
    }

    @Test
    fun `defaults expose the canonical Norma metadata`() {
        val mode = NormaGameMode()
        assertThat(mode.id().value).isEqualTo("norma")
        assertThat(mode.titleKey().value).isEqualTo("astera.gamemode.norma.title")
        assertThat(mode.tickPeriodTicks()).isEqualTo(20L)
        assertThat(mode.defaultPhases()).containsExactly(
            IGameModePhase.Lobby,
            IGameModePhase.Countdown,
            IGameModePhase.Active,
            IGameModePhase.SuddenDeath,
            IGameModePhase.Ended,
        )
    }
}
