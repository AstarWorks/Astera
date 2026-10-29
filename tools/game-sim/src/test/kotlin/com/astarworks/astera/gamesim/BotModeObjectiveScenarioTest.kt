package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.IneffectiveTargets
import com.astarworks.astera.bots.ModePlanner
import com.astarworks.astera.bots.StageObjectives
import com.astarworks.astera.bots.TargetPolicy
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.TeamId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Bots play the mode's objective, not just "find enemies" (item 25d). The planner ([ModePlanner], the object BotBrain asks
 * for its destination) is fed the REAL stage regions (StageObjectives.load) and drives a sim player through the REAL
 * mode:
 * in carry-tnt it wears the shield down, holds the enemy checkpoints and the deliver pedestal (the goal is a knockout).
 * Also: the Tab-list independent target fallback.
 */
class BotModeObjectiveScenarioTest {

    private fun objectives(lane: String): StageObjectives =
        checkNotNull(StageObjectives.load(GameSim.defaultContentDir().toFile(), lane)) { "stage of lane $lane did not load" }

    private fun planner(mode: String, name: String, team: String, enemy: String): ModePlanner {
        val src = objectives(mode)
        return ModePlanner(mode, name, team, listOf(enemy), checkNotNull(src.zones()))
    }

    /** Teleports the sim player to each destination the planner names; the planner holds there (pickups and deliveries are short holds). */
    private fun ScenarioScope.drive(runner: SimEntity, planner: ModePlanner, maxRounds: Int, matchId: com.astarworks.astera.core.domain.match.MatchId, done: () -> Boolean): List<String> {
        val kinds = ArrayList<String>()
        var now = 0L
        var held = 0
        var confirmed = 0
        repeat(maxRounds) {
            if (done()) return kinds
            // The server confirms a pickup to the holder: the sim's carried count rising is that signal.
            val have = sim.objectiveStage.carriedCount(runner.id)
            if (have > held) confirmed += have - held
            held = have
            planner.noteConfirmed(confirmed)
            val step = checkNotNull(planner.step(now, runner.position.x, runner.position.z)) { "mode has a plan" }
            if (kinds.lastOrNull() != step.kind) kinds += step.kind
            runner.moveTo(Vec3(step.x, 65.0, step.z))
            if (planner.clickWanted) clickNearestMarker(runner, matchId)
            advance(10)
            now += 500
        }
        return kinds
    }

    @Test
    fun `carry-tnt bot breaks the shield, holds the checkpoints and reaches the goal`() {
        scenario("bot-tnt-objective") {
            val runner = player("runner", at = Vec3(0.0, 65.0, -50.0))
            val guard = player("guard", at = Vec3(0.0, 65.0, -50.0))
            val matchId = startMatch("carry-tnt", mapOf(runner to "red", guard to "blue"), stage = "siege-isles-tnt")
            val kinds = drive(runner, planner("carry-tnt", "runner", "red", "blue"), 900, matchId) { eventsOf<MatchEnded>().isNotEmpty() }
            assertThat(kinds.filter { it != "checkpoint" }.take(2)).containsExactly("tnt", "deliver")
            assertThat(eventsOf<MatchEnded>().singleOrNull()?.winningTeam).isEqualTo(TeamId("red"))
        }
    }

    @Test
    fun `an enemy missing from the Tab list is still a target, and an untouchable one is dropped after no hit lands`() {
        // Hidden enemy: no name / team / game mode known. A listed team mate is still never a target.
        val hidden = TargetPolicy.Candidate(id = 7, name = "?uuid", team = null, spectating = false, x = 0.0, y = 65.0, z = 8.0, listed = false)
        val mate = TargetPolicy.Candidate(id = 8, name = "mate", team = "red", spectating = false, x = 0.0, y = 65.0, z = 3.0)
        assertThat(TargetPolicy.nearest("me", "red", 0.0, 65.0, 0.0, listOf(hidden, mate), 70.0)?.id).isEqualTo(7)

        // It is a corpse in the death cam (no Tab game mode to tell): 5 s and 3 attacks without a hit -> ignored for a while.
        val ineffective = IneffectiveTargets()
        var attacks = 0
        var now = 0L
        repeat(60) { ineffective.engage(7, now, hits = 0, attacks = attacks); now += 100; if (it % 10 == 0) attacks++ }
        assertThat(ineffective.ignored(7, now)).isTrue()
        assertThat(ineffective.ignored(7, now + 13_000)).`as`("only for a while").isFalse()

        // A real enemy that takes hits is never dropped.
        val real = IneffectiveTargets()
        var hits = 0; attacks = 0; now = 0
        repeat(200) { real.engage(9, now, hits, attacks); now += 100; if (it % 10 == 0) { attacks++; hits++ } }
        assertThat(real.ignored(9, now)).isFalse()
    }
}
