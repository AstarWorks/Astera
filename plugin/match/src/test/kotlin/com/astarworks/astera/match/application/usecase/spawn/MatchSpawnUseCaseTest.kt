package com.astarworks.astera.match.application.usecase.spawn

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerPortalReady
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.SpawnRole
import com.astarworks.astera.core.domain.stage.SpawnPoint
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.contents.StageContents
import com.astarworks.astera.match.application.service.DefaultSpawnSelector
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [MatchSpawnUseCase].
 *
 * WP9: spawns are per team; the player sits on team `red`.
 */
class MatchSpawnUseCaseTest {

    private lateinit var stages: StageContents
    private lateinit var matches: InMemoryMatchRepository
    private lateinit var worldGateway: FakeWorldGateway
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var useCase: MatchSpawnUseCase

    private val stageId = StageId("test-arena")
    private val matchId = MatchId("match-test")
    private val playerId = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val attackerPos = Vec3(10.0, 64.0, 20.0)
    private val testWorld = WorldId("world")

    @BeforeEach
    fun setUp() {
        stages = StageContents()
        matches = InMemoryMatchRepository()
        worldGateway = FakeWorldGateway()
        eventBus = FakeDomainEventBus()
        useCase = MatchSpawnUseCase(
            matchRepository = matches,
            stageRegistry = stages,
            worldGateway = worldGateway,
            eventBus = eventBus,
            spawnSelector = DefaultSpawnSelector(matches),
        )
    }

    @Test
    fun `happy path - teleports player to the matching spawn`() {
        stages.register(stageWithSpawns(red(attackerPos)))
        matches.save(matchOn(stageId))

        val result = useCase.spawnPlayer(matchId, playerId)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(worldGateway.teleports).hasSize(1)
        val call = worldGateway.teleports.single()
        assertThat(call.playerId).isEqualTo(playerId)
        assertThat(call.at).isEqualTo(attackerPos)
        assertThat(call.world).isEqualTo(testWorld)
        // Wave 5e: cross-server portal-ready signal must fire on happy path.
        val portals = eventBus.publishedOfType<PlayerPortalReady.EntryReady>()
        assertThat(portals).hasSize(1)
        assertThat(portals.single().playerId).isEqualTo(playerId)
        assertThat(portals.single().matchId).isEqualTo(matchId)
    }

    @Test
    fun `MatchNotFound when match does not exist`() {
        // Stage exists but no match is saved.
        stages.register(stageWithSpawns(red(attackerPos)))

        val result = useCase.spawnPlayer(matchId, playerId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(SpawnError.MatchNotFound::class.java)
        assertThat((error as SpawnError.MatchNotFound).matchId).isEqualTo(matchId)
        assertThat(worldGateway.teleports).isEmpty()
    }

    @Test
    fun `StageNotFound when match references missing stage`() {
        // Match referencing a stage that was never saved.
        matches.save(matchOn(StageId("ghost-stage")))

        val result = useCase.spawnPlayer(matchId, playerId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(SpawnError.StageNotFound::class.java)
        assertThat((error as SpawnError.StageNotFound).stageId).isEqualTo(StageId("ghost-stage"))
        assertThat(worldGateway.teleports).isEmpty()
    }

    @Test
    fun `NoSpawnPoint when stage has no spawn for the player's team`() {
        // Stage only has a blue spawn; the player is on red.
        stages.register(stageWithSpawns(blue(Vec3(0.0, 64.0, 0.0))))
        matches.save(matchOn(stageId))

        val result = useCase.spawnPlayer(matchId, playerId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(SpawnError.NoSpawnPoint::class.java)
        val noSpawn = error as SpawnError.NoSpawnPoint
        assertThat(noSpawn.playerId).isEqualTo(playerId)
        assertThat(noSpawn.stageId).isEqualTo(stageId)
        assertThat(worldGateway.teleports).isEmpty()
    }

    @Test
    fun `TeleportFailed when world gateway returns false`() {
        stages.register(stageWithSpawns(red(attackerPos)))
        matches.save(matchOn(stageId))
        worldGateway.teleportResult = false

        val result = useCase.spawnPlayer(matchId, playerId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(SpawnError.TeleportFailed::class.java)
        assertThat((error as SpawnError.TeleportFailed).playerId).isEqualTo(playerId)
        // The teleport was attempted exactly once before failing.
        assertThat(worldGateway.teleports).hasSize(1)
        // Wave 5e: portal-ready must NOT fire when teleport fails.
        assertThat(eventBus.publishedOfType<PlayerPortalReady.EntryReady>()).isEmpty()
    }

    private fun red(pos: Vec3): SpawnPoint =
        SpawnPoint(world = testWorld, position = pos, role = SpawnRole.TEAM, team = TeamId("red"))

    private fun blue(pos: Vec3): SpawnPoint =
        SpawnPoint(world = testWorld, position = pos, role = SpawnRole.TEAM, team = TeamId("blue"))

    private fun stageWithSpawns(vararg spawns: SpawnPoint): Stage = Stage(
        id = stageId,
        displayNameKey = MessageKey("astera.stage.test-arena.name"),
        regions = emptyList(),
        spawnPoints = spawns.toList(),
    )

    private fun matchOn(stage: StageId): Match = Match(
        id = matchId,
        phase = MatchPhase.WAITING,
        teams = listOf(
            Team(
                id = TeamId("red"),
                displayNameKey = MessageKey("astera.match.team.red"),
                colorKey = MessageKey("astera.match.team.red.color"),
                members = setOf(playerId),
            ),
        ),
        score = Score(mapOf(TeamId("red") to 0)),
        stageId = stage,
        startedAtTick = 0L,
    )
}
