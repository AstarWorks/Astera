package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.domain.match.PhaseTimings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VictoryConditionEvaluatorTest {

    private val evaluator = VictoryConditionEvaluator()

    @Test
    fun `team A wins when it reaches the threshold and leads`() {
        val match = buildMatch(
            scores = mapOf(
                TeamId("red") to PhaseTimings.SCORE_THRESHOLD,
                TeamId("blue") to 5,
            ),
        )

        assertThat(evaluator.evaluate(match)).isEqualTo(TeamId("red"))
    }

    @Test
    fun `team A exceeds the threshold and still wins`() {
        val match = buildMatch(
            scores = mapOf(
                TeamId("red") to PhaseTimings.SCORE_THRESHOLD + 3,
                TeamId("blue") to 7,
            ),
        )

        assertThat(evaluator.evaluate(match)).isEqualTo(TeamId("red"))
    }

    @Test
    fun `no winner when both teams are below threshold`() {
        val match = buildMatch(
            scores = mapOf(
                TeamId("red") to PhaseTimings.SCORE_THRESHOLD - 1,
                TeamId("blue") to PhaseTimings.SCORE_THRESHOLD - 1,
            ),
        )

        assertThat(evaluator.evaluate(match)).isNull()
    }

    @Test
    fun `no winner when leader is below threshold even if ahead`() {
        val match = buildMatch(
            scores = mapOf(
                TeamId("red") to PhaseTimings.SCORE_THRESHOLD - 1,
                TeamId("blue") to 0,
            ),
        )

        assertThat(evaluator.evaluate(match)).isNull()
    }

    @Test
    fun `tie at threshold returns null`() {
        val match = buildMatch(
            scores = mapOf(
                TeamId("red") to PhaseTimings.SCORE_THRESHOLD,
                TeamId("blue") to PhaseTimings.SCORE_THRESHOLD,
            ),
        )

        assertThat(evaluator.evaluate(match)).isNull()
    }

    @Test
    fun `tie above threshold returns null`() {
        val match = buildMatch(
            scores = mapOf(
                TeamId("red") to PhaseTimings.SCORE_THRESHOLD + 2,
                TeamId("blue") to PhaseTimings.SCORE_THRESHOLD + 2,
            ),
        )

        assertThat(evaluator.evaluate(match)).isNull()
    }

    @Test
    fun `empty score map returns null`() {
        val match = buildMatch(scores = emptyMap())
        assertThat(evaluator.evaluate(match)).isNull()
    }

    @Test
    fun `three-way race - unique leader at threshold wins`() {
        val match = buildMatch(
            scores = mapOf(
                TeamId("red") to PhaseTimings.SCORE_THRESHOLD,
                TeamId("blue") to PhaseTimings.SCORE_THRESHOLD - 1,
                TeamId("green") to 4,
            ),
        )

        assertThat(evaluator.evaluate(match)).isEqualTo(TeamId("red"))
    }

    @Test
    fun `three-way race - two-way tie at top returns null`() {
        val match = buildMatch(
            scores = mapOf(
                TeamId("red") to PhaseTimings.SCORE_THRESHOLD,
                TeamId("blue") to PhaseTimings.SCORE_THRESHOLD,
                TeamId("green") to 4,
            ),
        )

        assertThat(evaluator.evaluate(match)).isNull()
    }

    private fun buildMatch(scores: Map<TeamId, Int>): Match {
        // Match.init requires at least one team; supply a placeholder if scores is empty.
        val teams = if (scores.isEmpty()) {
            listOf(
                Team(
                    id = TeamId("placeholder"),
                    displayNameKey = MessageKey("astera.match.team.placeholder"),
                    colorKey = MessageKey("astera.match.team.placeholder.color"),
                    members = emptySet(),
                ),
            )
        } else {
            scores.keys.map { id ->
                Team(
                    id = id,
                    displayNameKey = MessageKey("astera.match.team.${id.value}"),
                    colorKey = MessageKey("astera.match.team.${id.value}.color"),
                    members = emptySet(),
                )
            }
        }
        return Match(
            id = MatchId("match-victory-test"),
            phase = MatchPhase.ACTIVE,
            teams = teams,
            score = Score(scores),
            stageId = StageId("test-arena"),
            startedAtTick = 0L,
        )
    }
}
