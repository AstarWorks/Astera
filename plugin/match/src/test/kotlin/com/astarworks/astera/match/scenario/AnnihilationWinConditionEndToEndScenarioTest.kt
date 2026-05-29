package com.astarworks.astera.match.scenario

import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.TeamEliminated
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.NexusId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.eventbus.listeners.victory.AnnihilationWinConditionListener
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.usecase.match.EndMatchUseCase
import com.astarworks.astera.match.application.usecase.match.EntryGameUseCase
import com.astarworks.astera.match.application.usecase.match.StartMatchUseCase
import com.astarworks.astera.match.application.usecase.match.SwitchPhaseUseCase
import com.astarworks.astera.match.application.usecase.match.VictoryConditionEvaluator
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import com.astarworks.astera.match.domain.match.MatchStateMachine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Wave I 5match-wireup-fix (ADR-0126 §A) end-to-end regression test.
 *
 * # The bug this guards against
 *
 * Round 10 Wave D 5game-complete (ADR-0121) introduced
 * [AnnihilationWinConditionListener] but never wired its
 * `eventBus.subscribe(TeamEliminated::class)` / `AnnihilationPhaseTransitioned`
 * calls in `AsteraMatchPlugin.onEnable`. With no subscriber,
 * `AnnihilationMatchTracker.evaluateWinCondition` was read-only — a 1v1
 * Annihilation match where one team's Tier-3 pillar was destroyed
 * never produced a [MatchEnded] event. The Round 13 audit caught the
 * gap as "Annihilation will never end".
 *
 * # What this test asserts
 *
 * 1. Start an Annihilation match with two teams (red / blue), each
 *    seeded into the AnnihilationMatchTracker via the same path
 *    `AnnihilationGameMode` would use at MatchStarted.
 * 2. Damage red's Tier-3 pillar to zero — the tracker publishes
 *    [TeamEliminated] which the listener subscribes to.
 * 3. Listener calls [IMatchLifecycle.end] which fires [MatchEnded].
 *
 * If any single step in that chain regresses (subscribe missed,
 * tracker mis-keyed, listener bypasses the lifecycle facade), the
 * `MatchEnded` assertion at the end of this test fails — the bug is
 * caught here before reaching prod again.
 *
 * # Why a bespoke harness wiring
 *
 * [MatchScenarioHarness.newDefault] intentionally does **not** wire
 * `AnnihilationWinConditionListener` — that harness's role is the
 * §C/§D/§E listener trio. The win-condition listener is large enough
 * (two event paths + sudden-death scheduler) that wiring it inside
 * the harness would bias every other scenario's event ordering.
 * Instead we wire it locally here, mirroring `AsteraMatchPlugin`
 * exactly:
 *
 *   - listener subscribes to both `TeamEliminated` +
 *     `AnnihilationPhaseTransitioned`
 *   - lifecycle façade is the same `IMatchLifecycle` the plugin
 *     publishes
 */
class AnnihilationWinConditionEndToEndScenarioTest {

    @Test
    fun `tier 3 destruction publishes TeamEliminated which ends the match via lifecycle`() {
        val h = MatchScenarioHarness.newDefault()

        // Wire the listener the way AsteraMatchPlugin does (Wave I 5match-wireup-fix §A).
        // Build a fresh IMatchLifecycle façade from the use cases the harness exposes
        // (the harness's private use-case wiring is internal so we construct a sibling
        // here; both share the same repo + bus + scheduler).
        val start = StartMatchUseCase(h.stages, h.matches, h.eventBus, h.scheduler)
        val end = EndMatchUseCase(h.matches, h.eventBus, h.scheduler, VictoryConditionEvaluator())
        val entry = EntryGameUseCase(h.matches, h.eventBus)
        val switch = SwitchPhaseUseCase(h.matches, h.eventBus, h.scheduler, h.modeRegistry)
        val lifecycle: IMatchLifecycle = object : IMatchLifecycle {
            override fun start(stageId: StageId, modeKey: String?) =
                start.execute(stageId, modeKey = modeKey)
            override fun end(matchId: MatchId, reason: EndReason) = end.execute(matchId, reason)
            override fun entry(
                matchId: MatchId,
                playerId: com.astarworks.astera.core.domain.player.PlayerId,
                teamId: TeamId?,
            ) = entry.execute(matchId, playerId, teamId)
            override fun switchPhase(
                matchId: MatchId,
                trigger: MatchStateMachine.TransitionTrigger,
            ) = switch.execute(matchId, trigger)
        }
        val winListener = AnnihilationWinConditionListener(
            tracker = h.annihilationTracker,
            lifecycle = lifecycle,
            scheduler = h.scheduler,
            eventBus = h.eventBus,
        )
        // Listener owns both subscription paths internally.
        winListener.subscribe()

        // 1v1 Annihilation
        val red = TeamId("red")
        val blue = TeamId("blue")
        val r1 = h.spawnPlayer("r1")
        val b1 = h.spawnPlayer("b1")

        val matchId = h.startMatch(
            StageId("annihilation-arena"),
            modeKey = StartMatchUseCase.MODE_KEY_ANNIHILATION,
        )
        h.entry(matchId, r1, red)
        h.entry(matchId, b1, blue)

        // Promote past COUNTDOWN (2 players is below the standard 3-team floor of 3,
        // but a 1v1 only needs 2 — matches the registry default for "annihilation"
        // and is enough to exercise the listener path).
        // For the purpose of THIS test we do not need the COUNTDOWN→ACTIVE flip;
        // the listener subscribes to TeamEliminated, which fires from the tracker
        // regardless of MatchPhase. We seed the tracker and damage a pillar directly.

        // Seed the tracker with both teams (AnnihilationGameMode would do this
        // via `inferTeams` during `onMatchStarted` — we mirror that contract here).
        val cfg = AnnihilationConfig.DEFAULT
        h.annihilationTracker.register(
            matchId = matchId,
            config = cfg,
            teams = listOf(red, blue),
            atTick = h.scheduler.currentTick(),
        )

        // Pillars seed in BUILDING state — Tier 3 is only damageable in
        // Phase3Tier3+. Advance the tracker through Phase 2 (buildPhaseTicks
        // elapsed) then Phase 3 (engagePhaseTicks elapsed) so the Tier-3
        // pillar opens for combat. This mirrors what AnnihilationGameMode.onTick
        // does in production: poll the phase rules each tick.
        h.advanceTicks(cfg.buildPhaseTicks)
        h.annihilationTracker.advancePhasesIfNeeded(matchId, h.scheduler.currentTick())
        h.advanceTicks(cfg.engagePhaseTicks)
        h.annihilationTracker.advancePhasesIfNeeded(matchId, h.scheduler.currentTick())

        // Destroy red's Tier-3 pillar — this synthesises the same TeamEliminated
        // path the production code takes when the Wave 6 Paper adapter routes
        // block-break events into `recordDamage`.
        val redTier3Id = NexusId("${matchId.value}-${red.value}-3")
        h.annihilationTracker.recordDamage(
            matchId = matchId,
            nexusId = redTier3Id,
            amount = cfg.pillarHp,
            attackerId = b1,
            atTick = h.scheduler.currentTick(),
        )

        // Sanity: tracker fired TeamEliminated for red.
        val eliminations = h.published<TeamEliminated>()
            .filter { it.matchId == matchId }
        assertThat(eliminations).hasSize(1)
        assertThat(eliminations.single().teamId).isEqualTo(red)

        // The critical assertion: AnnihilationWinConditionListener observed
        // the elimination and called IMatchLifecycle.end, which fired MatchEnded.
        val ends = h.published<MatchEnded>().filter { it.matchId == matchId }
        assertThat(ends)
            .describedAs(
                "AnnihilationWinConditionListener wire-up missing — Wave I 5match-wireup-fix " +
                    "regressed if this is empty. The listener should subscribe to TeamEliminated " +
                    "and call IMatchLifecycle.end on a Winner outcome.",
            )
            .hasSize(1)
        assertThat(ends.single().reason).isEqualTo(EndReason.ObjectiveCompleted)
        // Note: MatchEnded.winningTeam is computed by VictoryConditionEvaluator
        // (Norma-specific). For Annihilation the team identity lives in the
        // listener's debug log + the AnnihilationMatchTracker state. The
        // end-event chain itself (the regression target) is the load-bearing
        // assertion here. The Wave 6 Paper-impl ADR-0108 §"MatchEnded fields"
        // tracks adding Annihilation-aware winningTeam population.
    }

    @Test
    fun `subscribe returns both TeamEliminated and AnnihilationPhaseTransitioned subscriptions`() {
        val h = MatchScenarioHarness.newDefault()

        val end2 = EndMatchUseCase(h.matches, h.eventBus, h.scheduler, VictoryConditionEvaluator())
        val lifecycle: IMatchLifecycle = object : IMatchLifecycle {
            override fun start(stageId: StageId, modeKey: String?) = error("not used")
            override fun end(matchId: MatchId, reason: EndReason) = end2.execute(matchId, reason)
            override fun entry(
                matchId: MatchId,
                playerId: com.astarworks.astera.core.domain.player.PlayerId,
                teamId: TeamId?,
            ) = error("not used")
            override fun switchPhase(
                matchId: MatchId,
                trigger: MatchStateMachine.TransitionTrigger,
            ) = error("not used")
        }
        val listener = AnnihilationWinConditionListener(
            tracker = h.annihilationTracker,
            lifecycle = lifecycle,
            scheduler = h.scheduler,
            eventBus = h.eventBus,
        )
        val subs = listener.subscribe()
        // 2 paths: TeamEliminated + AnnihilationPhaseTransitioned.
        assertThat(subs)
            .describedAs(
                "AnnihilationWinConditionListener.subscribe() must return both event " +
                    "subscriptions so AsteraMatchPlugin's onDisable cleans both paths " +
                    "(ADR-0123 §4 listener cleanup audit).",
            )
            .hasSize(2)
    }
}
