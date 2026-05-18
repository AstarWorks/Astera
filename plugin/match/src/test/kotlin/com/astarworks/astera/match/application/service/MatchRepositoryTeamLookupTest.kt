package com.astarworks.astera.match.application.service

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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5a — MatchRepositoryTeamLookup adapts the Match plugin's repository
 * into Combat's `IMatchTeamLookup` port. Verifies the lookup walks
 * `IMatchRepository.findMatchOf` → `match.teams` correctly, and returns
 * `null` for players not registered in any tracked match.
 */
class MatchRepositoryTeamLookupTest {

    private lateinit var matchRepository: InMemoryMatchRepository
    private lateinit var lookup: MatchRepositoryTeamLookup

    private val red = TeamId("red")
    private val blue = TeamId("blue")
    private val matchId = MatchId("match-1")

    private val redPlayer = PlayerId(UUID.randomUUID())
    private val bluePlayer = PlayerId(UUID.randomUUID())
    private val outsider = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        matchRepository = InMemoryMatchRepository()
        lookup = MatchRepositoryTeamLookup(matchRepository)
    }

    @Test
    fun `teamOf returns the team containing the player`() {
        matchRepository.save(
            buildMatch(
                redMembers = setOf(redPlayer),
                blueMembers = setOf(bluePlayer),
            ),
        )

        assertThat(lookup.teamOf(redPlayer)).isEqualTo(red)
        assertThat(lookup.teamOf(bluePlayer)).isEqualTo(blue)
    }

    @Test
    fun `teamOf returns null when player is not in any match`() {
        matchRepository.save(
            buildMatch(
                redMembers = setOf(redPlayer),
                blueMembers = setOf(bluePlayer),
            ),
        )

        assertThat(lookup.teamOf(outsider)).isNull()
    }

    @Test
    fun `teamOf returns null when repository has no matches`() {
        assertThat(lookup.teamOf(redPlayer)).isNull()
    }

    @Test
    fun `teamOf returns the correct team in a multi-team match with empty teams`() {
        // Edge case: match exists with an additional empty team. findMatchOf
        // resolves via membership, so the player must still map to *their*
        // team (not the first one or the empty one).
        val third = TeamId("green")
        val match = Match(
            id = matchId,
            phase = MatchPhase.ACTIVE,
            teams = listOf(
                Team(third, MessageKey("astera.match.team.green"), MessageKey("astera.match.team.green.color"), emptySet()),
                Team(red, MessageKey("astera.match.team.red"), MessageKey("astera.match.team.red.color"), setOf(redPlayer)),
                Team(blue, MessageKey("astera.match.team.blue"), MessageKey("astera.match.team.blue.color"), setOf(bluePlayer)),
            ),
            score = Score(mapOf(red to 0, blue to 0, third to 0)),
            stageId = StageId("test-arena"),
            startedAtTick = 0L,
        )
        matchRepository.save(match)

        assertThat(lookup.teamOf(redPlayer)).isEqualTo(red)
        assertThat(lookup.teamOf(bluePlayer)).isEqualTo(blue)
    }

    private fun buildMatch(
        redMembers: Set<PlayerId>,
        blueMembers: Set<PlayerId>,
    ): Match = Match(
        id = matchId,
        phase = MatchPhase.ACTIVE,
        teams = listOf(
            Team(
                id = red,
                displayNameKey = MessageKey("astera.match.team.red"),
                colorKey = MessageKey("astera.match.team.red.color"),
                members = redMembers,
            ),
            Team(
                id = blue,
                displayNameKey = MessageKey("astera.match.team.blue"),
                colorKey = MessageKey("astera.match.team.blue.color"),
                members = blueMembers,
            ),
        ),
        score = Score(mapOf(red to 0, blue to 0)),
        stageId = StageId("test-arena"),
        startedAtTick = 0L,
    )
}
