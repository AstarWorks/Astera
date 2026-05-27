package com.astarworks.astera.match.scenario

import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.usecase.match.StartMatchUseCase
import com.astarworks.astera.match.domain.death.PvpDeathRule
import com.astarworks.astera.match.domain.match.MatchMinPlayerPolicy
import com.astarworks.astera.match.domain.match.MatchStateMachine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Wave 5game-complete (ADR-0121 §G) scenario #2.
 *
 * **End-to-end Annihilation 3v3v3** (3 teams × 3 players = 9 players).
 *
 * Phase 1 PoC asserts the wiring chain that ADR-0121 §A landed:
 *
 *  1. Match starts with `modeKey = "annihilation"` and 3-team
 *     formation (red / blue / yellow).
 *  2. The mode registry mirrors the modeKey so per-match policies see
 *     `annihilation` instead of `null`.
 *  3. min-player guard's `requirementFor("annihilation") == 3` so any
 *     COUNTDOWN→ACTIVE attempt with fewer than 3 enrolled players is
 *     rejected with `InsufficientPlayers`.
 *  4. PvP death policy defaults to [PvpDeathRule.DropEquipped] for
 *     Annihilation (vs [PvpDeathRule.KeepInventory] for Norma).
 *  5. `IMatchLifecycle.end(SuddenDeathResolved)` admin-equivalent
 *     produces a `MatchEnded` event the scenario can assert on.
 *
 * **Why no actual nexus-break simulation here**
 *
 * The nexus-break path needs world-geometry (`IBlockStateGateway` +
 * `IBarrierGateway`) which the in-memory harness doesn't wire. The
 * gameplay-loop end → MatchEnded chain is unit-tested separately in
 * `AnnihilationWinConditionListenerTest`. This scenario specifically
 * verifies the **integration boundaries** (mode resolution + min-player
 * + PvP death policy + end-of-match event fan-out).
 */
class AnnihilationThreeVThreeCoreBreakScenarioTest {

    @Test
    fun `annihilation 3v3v3 end-to-end integration boundaries`() {
        val h = MatchScenarioHarness.newDefault()

        val red = TeamId("red")
        val blue = TeamId("blue")
        val yellow = TeamId("yellow")

        // 9 players, 3 per team.
        val redMembers = listOf("r1", "r2", "r3").map { h.spawnPlayer(it) }
        val blueMembers = listOf("b1", "b2", "b3").map { h.spawnPlayer(it) }
        val yellowMembers = listOf("y1", "y2", "y3").map { h.spawnPlayer(it) }

        val matchId = h.startMatch(
            StageId("annihilation-arena"),
            modeKey = StartMatchUseCase.MODE_KEY_ANNIHILATION,
        )
        redMembers.forEach { h.entry(matchId, it, red) }
        blueMembers.forEach { h.entry(matchId, it, blue) }
        yellowMembers.forEach { h.entry(matchId, it, yellow) }

        // §F: mode registry returns the modeKey we registered.
        assertThat(h.modeRegistry.modeKeyOf(matchId)).isEqualTo("annihilation")

        // §F: min-player floor for Annihilation is 3.
        assertThat(
            MatchMinPlayerPolicy.requirementFor(StartMatchUseCase.MODE_KEY_ANNIHILATION),
        ).isEqualTo(MatchMinPlayerPolicy.ANNIHILATION_MIN_PLAYERS)

        // 9 players ≥ 3 floor: COUNTDOWN → ACTIVE passes.
        val toActive = h.switchPhase(matchId, MatchStateMachine.TransitionTrigger.CountdownComplete)
        assertThat(toActive.isOk()).isTrue()

        // §E: PvP death policy default for Annihilation is DropEquipped.
        assertThat(h.pvpResolver.resolveForMatch(matchId)).isEqualTo(PvpDeathRule.DropEquipped)

        // End the match — the matched MatchEnded is the integration assertion.
        h.endMatch(matchId, EndReason.SuddenDeathResolved)

        // Event-chain assertions
        val starts = h.published<MatchStarted>().filter { it.matchId == matchId }
        assertThat(starts).hasSize(1)
        assertThat(starts.single().modeKey).isEqualTo("annihilation")

        val ends = h.published<MatchEnded>().filter { it.matchId == matchId }
        assertThat(ends).hasSize(1)
        assertThat(ends.single().reason).isEqualTo(EndReason.SuddenDeathResolved)

        // §E aftermath: modeRegistry is cleared on MatchEnded.
        assertThat(h.modeRegistry.modeKeyOf(matchId)).isNull()
    }

    @Test
    fun `annihilation rejects COUNTDOWN to ACTIVE with only 2 enrolled players`() {
        val h = MatchScenarioHarness.newDefault()

        val matchId = h.startMatch(
            StageId("annihilation-arena"),
            modeKey = StartMatchUseCase.MODE_KEY_ANNIHILATION,
        )
        // 2 players in a 3-team match → below floor of 3.
        h.entry(matchId, h.spawnPlayer("solo-r"), TeamId("red"))
        h.entry(matchId, h.spawnPlayer("solo-b"), TeamId("blue"))

        val result = h.switchPhase(matchId, MatchStateMachine.TransitionTrigger.CountdownComplete)
        assertThat(result.isErr()).isTrue()
        val err = (result as com.astarworks.astera.core.domain.Result.Failure).error
        assertThat(err).isInstanceOf(
            com.astarworks.astera.match.application.usecase.match.SwitchPhaseError.InsufficientPlayers::class.java,
        )
        val cast = err as com.astarworks.astera.match.application.usecase.match.SwitchPhaseError.InsufficientPlayers
        assertThat(cast.required).isEqualTo(3)
        assertThat(cast.actual).isEqualTo(2)
    }
}

private fun <T, E> com.astarworks.astera.core.domain.Result<T, E>.isOk(): Boolean =
    this is com.astarworks.astera.core.domain.Result.Success

private fun <T, E> com.astarworks.astera.core.domain.Result<T, E>.isErr(): Boolean =
    this is com.astarworks.astera.core.domain.Result.Failure
