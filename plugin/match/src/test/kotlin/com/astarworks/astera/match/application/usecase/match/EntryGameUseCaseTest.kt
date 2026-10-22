package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.stage.SpawnPoint
import com.astarworks.astera.core.domain.stage.SpawnRole
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.contents.StageContents
import com.astarworks.astera.match.application.service.DefaultSpawnSelector
import com.astarworks.astera.match.application.usecase.spawn.MatchSpawnUseCase
import com.astarworks.astera.testfixtures.FakeWorldGateway
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class EntryGameUseCaseTest {

    private lateinit var matches: InMemoryMatchRepository
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var useCase: EntryGameUseCase

    private val matchId = MatchId("match-abc")
    private val playerId = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        matches = InMemoryMatchRepository()
        eventBus = FakeDomainEventBus()
        useCase = EntryGameUseCase(matches, eventBus)
    }

    @Test
    fun `happy path - player enters WAITING match`() {
        matches.save(buildMatch(matchId, MatchPhase.WAITING))

        val result = useCase.execute(matchId, playerId, null)

        assertThat(result).isInstanceOf(Result.Success::class.java)

        val saved = matches.findById(matchId)!!
        assertThat(saved.hasParticipant(playerId)).isTrue()
    }

    @Test
    fun `happy path - player enters COUNTDOWN match`() {
        matches.save(buildMatch(matchId, MatchPhase.COUNTDOWN))

        val result = useCase.execute(matchId, playerId, null)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(matches.findById(matchId)!!.hasParticipant(playerId)).isTrue()
    }

    @Test
    fun `MatchNotFound when match does not exist`() {
        val result = useCase.execute(MatchId("match-xyz"), playerId, null)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error)
            .isInstanceOf(EntryGameError.MatchNotFound::class.java)
    }

    @Test
    fun `EntryClosed when match is ACTIVE`() {
        matches.save(buildMatch(matchId, MatchPhase.ACTIVE))

        val result = useCase.execute(matchId, playerId, null)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(EntryGameError.EntryClosed::class.java)
    }

    @Test
    fun `AlreadyRegistered when player is already in the match`() {
        val teamId = TeamId("red")
        val matchWithPlayer = Match(
            id = matchId,
            phase = MatchPhase.WAITING,
            teams = listOf(
                Team(
                    id = teamId,
                    displayNameKey = MessageKey("astera.match.team.red"),
                    colorKey = MessageKey("astera.match.team.red.color"),
                    members = setOf(playerId),
                ),
            ),
            score = Score(mapOf(teamId to 0)),
            stageId = StageId("test-arena"),
            startedAtTick = 0L,
        )
        matches.save(matchWithPlayer)

        val result = useCase.execute(matchId, playerId, null)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error)
            .isInstanceOf(EntryGameError.AlreadyRegistered::class.java)
    }

    @Test
    fun `a player entering after MatchStarted (COUNTDOWN) is teleported to their team spawn`() {
        val stages = StageContents()
        val spawn = Vec3(-45.0, -60.0, 0.0)
        stages.register(
            Stage(
                id = StageId("test-arena"),
                displayNameKey = MessageKey("astera.stage.test-arena.name"),
                regions = emptyList(),
                spawnPoints = listOf(
                    SpawnPoint(WorldId("world"), spawn, role = SpawnRole.TEAM, team = TeamId("red")),
                ),
            ),
        )
        val world = FakeWorldGateway()
        val entry = EntryGameUseCase(
            matches, eventBus,
            MatchSpawnUseCase(matches, stages, world, eventBus, DefaultSpawnSelector(matches)),
        )
        matches.save(buildMatch(matchId, MatchPhase.COUNTDOWN))

        val result = entry.execute(matchId, playerId, null)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(world.teleports).hasSize(1)
        assertThat(world.teleports.single().playerId).isEqualTo(playerId)
        assertThat(world.teleports.single().at).isEqualTo(spawn)
    }

    private fun buildMatch(id: MatchId, phase: MatchPhase): Match {
        val teamId = TeamId("red")
        return Match(
            id = id,
            phase = phase,
            teams = listOf(
                Team(
                    id = teamId,
                    displayNameKey = MessageKey("astera.match.team.red"),
                    colorKey = MessageKey("astera.match.team.red.color"),
                    members = emptySet(),
                ),
            ),
            score = Score(mapOf(teamId to 0)),
            stageId = StageId("test-arena"),
            startedAtTick = 0L,
        )
    }
}
