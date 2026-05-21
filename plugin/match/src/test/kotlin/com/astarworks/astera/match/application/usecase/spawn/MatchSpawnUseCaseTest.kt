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
import com.astarworks.astera.core.domain.stage.SpawnFaction
import com.astarworks.astera.core.domain.stage.SpawnPoint
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.contents.StageContents
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
 * Wave 5o (ADR-0081): the assertions now construct `SpawnPoint` as a flat
 * data class with `faction` enum instead of the pre-Wave-5o
 * `SpawnPoint.Attacker(pos)` sealed-variant calls.
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
            // RandomSpawnRotationStrategy with a single matching spawn is
            // deterministic — no need to seed the Random for these tests.
            strategy = RandomSpawnRotationStrategy(),
        )
    }

    @Test
    fun `happy path - teleports player to the matching spawn`() {
        stages.register(stageWithSpawns(attacker(attackerPos)))
        matches.save(matchOn(stageId))

        val result = useCase.spawnPlayer(matchId, playerId, SpawnKind.ATTACKER)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(worldGateway.teleports).hasSize(1)
        val call = worldGateway.teleports.single()
        assertThat(call.playerId).isEqualTo(playerId)
        assertThat(call.at).isEqualTo(attackerPos)
        assertThat(call.world).isNull()
        // Wave 5e: cross-server portal-ready signal must fire on happy path.
        val portals = eventBus.publishedOfType<PlayerPortalReady.EntryReady>()
        assertThat(portals).hasSize(1)
        assertThat(portals.single().playerId).isEqualTo(playerId)
        assertThat(portals.single().matchId).isEqualTo(matchId)
    }

    @Test
    fun `MatchNotFound when match does not exist`() {
        // Stage exists but no match is saved.
        stages.register(stageWithSpawns(attacker(attackerPos)))

        val result = useCase.spawnPlayer(matchId, playerId, SpawnKind.ATTACKER)

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

        val result = useCase.spawnPlayer(matchId, playerId, SpawnKind.ATTACKER)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(SpawnError.StageNotFound::class.java)
        assertThat((error as SpawnError.StageNotFound).stageId).isEqualTo(StageId("ghost-stage"))
        assertThat(worldGateway.teleports).isEmpty()
    }

    @Test
    fun `NoSpawnPointForKind when stage has no spawn of the requested kind`() {
        // Stage only has a defender spawn — asking for attacker must fail.
        stages.register(stageWithSpawns(defender(Vec3(0.0, 64.0, 0.0))))
        matches.save(matchOn(stageId))

        val result = useCase.spawnPlayer(matchId, playerId, SpawnKind.ATTACKER)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(SpawnError.NoSpawnPointForKind::class.java)
        val noSpawn = error as SpawnError.NoSpawnPointForKind
        assertThat(noSpawn.kind).isEqualTo(SpawnKind.ATTACKER)
        assertThat(noSpawn.stageId).isEqualTo(stageId)
        assertThat(worldGateway.teleports).isEmpty()
    }

    @Test
    fun `TeleportFailed when world gateway returns false`() {
        stages.register(stageWithSpawns(attacker(attackerPos)))
        matches.save(matchOn(stageId))
        worldGateway.teleportResult = false

        val result = useCase.spawnPlayer(matchId, playerId, SpawnKind.ATTACKER)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(SpawnError.TeleportFailed::class.java)
        assertThat((error as SpawnError.TeleportFailed).playerId).isEqualTo(playerId)
        // The teleport was attempted exactly once before failing.
        assertThat(worldGateway.teleports).hasSize(1)
        // Wave 5e: portal-ready must NOT fire when teleport fails.
        assertThat(eventBus.publishedOfType<PlayerPortalReady.EntryReady>()).isEmpty()
    }

    private fun attacker(pos: Vec3): SpawnPoint =
        SpawnPoint(world = testWorld, position = pos, faction = SpawnFaction.ATTACKER)

    private fun defender(pos: Vec3): SpawnPoint =
        SpawnPoint(world = testWorld, position = pos, faction = SpawnFaction.DEFENDER)

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
                members = emptySet(),
            ),
        ),
        score = Score(mapOf(TeamId("red") to 0)),
        stageId = stage,
        startedAtTick = 0L,
    )
}
