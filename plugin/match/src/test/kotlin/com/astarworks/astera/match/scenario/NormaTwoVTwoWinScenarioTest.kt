package com.astarworks.astera.match.scenario

import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.domain.match.MatchStateMachine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Wave 5game-complete (ADR-0121 §G) scenario #1.
 *
 * **End-to-end Norma 2v2**:
 *
 *  1. Spawn 4 fake players, divide into red / blue (2 each).
 *  2. Start match (mode = `null` = Norma default).
 *  3. Enroll all 4 players via `EntryGameUseCase`.
 *  4. Switch COUNTDOWN → ACTIVE (min-player guard passes: 4 ≥ 2).
 *  5. Publish 2 kills (red wipes blue).
 *  6. Admin-end the match.
 *
 * Asserts the **full event chain** is published and observable:
 *
 *  - exactly one `MatchStarted` for the started match
 *  - 2 `PlayerKilled` (kill flow → bus → other listeners)
 *  - exactly one `MatchEnded` with `reason = AdminEnded`
 *  - the match's phase reaches `ENDED`
 */
class NormaTwoVTwoWinScenarioTest {

    @Test
    fun `norma 2v2 end-to-end publishes MatchStarted + 2 kills + MatchEnded`() {
        val h = MatchScenarioHarness.newDefault()

        val red = TeamId("red")
        val blue = TeamId("blue")
        val r1 = h.spawnPlayer("red-1")
        val r2 = h.spawnPlayer("red-2")
        val b1 = h.spawnPlayer("blue-1")
        val b2 = h.spawnPlayer("blue-2")

        val matchId = h.startMatch(StageId("norma-arena"), modeKey = null)
        h.entry(matchId, r1, red)
        h.entry(matchId, r2, red)
        h.entry(matchId, b1, blue)
        h.entry(matchId, b2, blue)

        // COUNTDOWN → ACTIVE: 4 players ≥ 2 floor.
        val toActive = h.switchPhase(matchId, MatchStateMachine.TransitionTrigger.CountdownComplete)
        assertThat(toActive.isSuccess()).isTrue()

        val sword = ItemId("amethyst-sword")
        h.publishKill(b1, r1, sword)
        h.publishKill(b2, r2, sword)

        val endResult = h.endMatch(matchId, EndReason.AdminEnded)
        assertThat(endResult).isNotNull()

        // Event-chain assertions
        val starts = h.published<MatchStarted>()
        assertThat(starts).hasSize(1)
        assertThat(starts.single().matchId).isEqualTo(matchId)

        val kills = h.published<com.astarworks.astera.combat.domain.event.PlayerKilled>()
        assertThat(kills).hasSize(2)
        assertThat(kills.map { it.victim }).containsExactlyInAnyOrder(b1, b2)

        val ends = h.published<MatchEnded>()
        assertThat(ends).hasSize(1)
        assertThat(ends.single().reason).isEqualTo(EndReason.AdminEnded)

        val finalPhase = h.matches.findById(matchId)?.phase
        assertThat(finalPhase).isEqualTo(MatchPhase.ENDED)
    }

    @Test
    fun `min-player guard rejects COUNTDOWN to ACTIVE when only 1 player enrolled`() {
        val h = MatchScenarioHarness.newDefault()

        val matchId = h.startMatch(StageId("norma-arena"), modeKey = null)
        h.entry(matchId, h.spawnPlayer("solo"), TeamId("red"))

        val result = h.switchPhase(matchId, MatchStateMachine.TransitionTrigger.CountdownComplete)
        assertThat(result.isFailure()).isTrue()
        val err = (result as com.astarworks.astera.core.domain.Result.Failure).error
        assertThat(err).isInstanceOf(
            com.astarworks.astera.match.application.usecase.match.SwitchPhaseError.InsufficientPlayers::class.java,
        )
    }
}

private fun <T, E> com.astarworks.astera.core.domain.Result<T, E>.isSuccess(): Boolean =
    this is com.astarworks.astera.core.domain.Result.Success

private fun <T, E> com.astarworks.astera.core.domain.Result<T, E>.isFailure(): Boolean =
    this is com.astarworks.astera.core.domain.Result.Failure
