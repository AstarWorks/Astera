package com.astarworks.astera.match.domain.match

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.StageId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class MatchMinPlayerPolicyTest {

    @Test
    fun `requirementFor returns 2 for null and unknown modeKey`() {
        assertThat(MatchMinPlayerPolicy.requirementFor(null)).isEqualTo(2)
        assertThat(MatchMinPlayerPolicy.requirementFor("unknown-mode")).isEqualTo(2)
    }

    @Test
    fun `requirementFor returns 3 for annihilation`() {
        assertThat(MatchMinPlayerPolicy.requirementFor(MatchMinPlayerPolicy.MODE_KEY_ANNIHILATION))
            .isEqualTo(MatchMinPlayerPolicy.ANNIHILATION_MIN_PLAYERS)
    }

    @Test
    fun `requirementFor returns 2 for bet 1v1`() {
        assertThat(MatchMinPlayerPolicy.requirementFor(MatchMinPlayerPolicy.MODE_KEY_BET_1V1))
            .isEqualTo(MatchMinPlayerPolicy.BET_1V1_MIN_PLAYERS)
    }

    @Test
    fun `check returns Ok when actual meets norma floor`() {
        val match = matchWith(red = 1, blue = 1)
        assertThat(MatchMinPlayerPolicy.check(modeKey = null, match = match))
            .isEqualTo(MatchMinPlayerPolicy.Outcome.Ok)
    }

    @Test
    fun `check returns Insufficient when annihilation has only 2 players`() {
        val match = matchWith(red = 1, blue = 1, yellow = 0)
        val out = MatchMinPlayerPolicy.check("annihilation", match)
        assertThat(out).isInstanceOf(MatchMinPlayerPolicy.Outcome.Insufficient::class.java)
        val cast = out as MatchMinPlayerPolicy.Outcome.Insufficient
        assertThat(cast.required).isEqualTo(3)
        assertThat(cast.actual).isEqualTo(2)
    }

    @Test
    fun `check returns Ok when annihilation has 3 players spread across teams`() {
        val match = matchWith(red = 1, blue = 1, yellow = 1)
        assertThat(MatchMinPlayerPolicy.check("annihilation", match))
            .isEqualTo(MatchMinPlayerPolicy.Outcome.Ok)
    }

    private fun matchWith(red: Int, blue: Int, yellow: Int = -1): Match {
        val teams = buildList {
            add(team("red", red))
            add(team("blue", blue))
            if (yellow >= 0) add(team("yellow", yellow))
        }
        return Match(
            id = MatchId("test-match"),
            phase = MatchPhase.COUNTDOWN,
            teams = teams,
            score = Score(teams.associate { it.id to 0 }),
            stageId = StageId("test-stage"),
            startedAtTick = 0L,
        )
    }

    private fun team(id: String, members: Int): Team = Team(
        id = TeamId(id),
        displayNameKey = MessageKey("astera.match.team.$id"),
        colorKey = MessageKey("astera.match.team.$id.color"),
        members = (1..members).map { PlayerId(UUID.randomUUID()) }.toSet(),
    )
}
