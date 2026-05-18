package com.astarworks.astera.match.application.usecase.world

import com.astarworks.astera.core.application.port.outbound.ISchematicLoader
import com.astarworks.astera.core.application.port.outbound.SchematicLoadError
import com.astarworks.astera.core.application.port.outbound.SchematicPlaceError
import com.astarworks.astera.core.application.service.InMemorySchematicLoader
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.Schematic
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.service.InMemoryMatchWorldRepository
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Wave 5d [CreateMatchWorldUseCase] tests.
 *
 * Pins:
 *  - happy path: a `MatchStarted` for which the schematic loader has a
 *    canned schematic results in a [com.astarworks.astera.match.domain.match.MatchWorld]
 *    saved under the default naming policy, and a placement is recorded
 *    against the loader.
 *  - soft-fail on missing schematic: the [com.astarworks.astera.match.domain.match.MatchWorld]
 *    is still saved (so downstream features have a target world id) and
 *    no placement is recorded.
 *  - soft-fail on placement failure: the [com.astarworks.astera.match.domain.match.MatchWorld]
 *    is still saved.
 *  - injected `schematicPathFor` / `worldIdFor` are honored.
 *  - `subscribe(...)` actually wires the handler — publishing a
 *    `MatchStarted` through the bus drives the same save.
 */
internal class CreateMatchWorldUseCaseTest {

    private lateinit var bus: FakeDomainEventBus
    private lateinit var matchWorlds: InMemoryMatchWorldRepository

    private val matchId = MatchId("create-test")
    private val stageId = StageId("test-stage")
    private val defaultPath: Path = Paths.get("match-worlds", "${matchId.value}.schem")
    private val defaultWorldId = WorldId("match-${matchId.value}")
    private val cannedSchematic = Schematic(path = defaultPath.toString(), size = Vec3(16.0, 16.0, 16.0), version = 3)

    @BeforeEach
    fun setUp() {
        bus = FakeDomainEventBus()
        matchWorlds = InMemoryMatchWorldRepository()
    }

    @Test
    fun `happy path - schematic loads and places, MatchWorld saved with defaults`() {
        val loader = InMemorySchematicLoader(initial = mapOf(defaultPath to cannedSchematic))
        val useCase = CreateMatchWorldUseCase(loader, matchWorlds)

        useCase.handle(MatchStarted(matchId = matchId, stageId = stageId, atTick = 1L))

        val saved = matchWorlds.findByMatch(matchId)
        assertThat(saved).isNotNull
        assertThat(saved!!.matchId).isEqualTo(matchId)
        assertThat(saved.worldId).isEqualTo(defaultWorldId)
        assertThat(saved.sourceSchematicPath).isEqualTo(defaultPath.toString())

        // Placement recorded at origin into the derived world id.
        assertThat(loader.placements()).hasSize(1)
        val placement = loader.placements().single()
        assertThat(placement.schematic).isEqualTo(cannedSchematic)
        assertThat(placement.world).isEqualTo(defaultWorldId)
        assertThat(placement.offset).isEqualTo(Vec3.ZERO)
    }

    @Test
    fun `subscribe wires the handler - publishing MatchStarted drives the save`() {
        val loader = InMemorySchematicLoader(initial = mapOf(defaultPath to cannedSchematic))
        val useCase = CreateMatchWorldUseCase(loader, matchWorlds)

        val subscription = useCase.subscribe(bus)
        bus.publish(MatchStarted(matchId = matchId, stageId = stageId, atTick = 7L))

        assertThat(subscription.isActive).isTrue()
        assertThat(matchWorlds.findByMatch(matchId)).isNotNull
        assertThat(loader.placements()).hasSize(1)
    }

    @Test
    fun `missing schematic - soft fail, MatchWorld still saved, no placement`() {
        // Loader has no canned schematic for the default path.
        val loader = InMemorySchematicLoader()
        val useCase = CreateMatchWorldUseCase(loader, matchWorlds)

        useCase.handle(MatchStarted(matchId = matchId, stageId = stageId, atTick = 1L))

        val saved = matchWorlds.findByMatch(matchId)
        assertThat(saved).isNotNull
        assertThat(saved!!.worldId).isEqualTo(defaultWorldId)
        assertThat(saved.sourceSchematicPath).isEqualTo(defaultPath.toString())
        assertThat(loader.placements()).isEmpty()
    }

    @Test
    fun `placement failure - soft fail, MatchWorld still saved`() {
        // Schematic loads, but the loader is configured to reject the target world.
        val rejectingLoader = object : ISchematicLoader {
            val placements: MutableList<Triple<Schematic, WorldId, Vec3>> = mutableListOf()

            override fun load(path: Path): Result<Schematic, SchematicLoadError> =
                Result.Success(cannedSchematic)

            override fun place(
                schematic: Schematic,
                world: WorldId,
                offset: Vec3,
            ): Result<Unit, SchematicPlaceError> {
                placements += Triple(schematic, world, offset)
                return Result.Failure(SchematicPlaceError.UnknownWorld(world))
            }
        }
        val useCase = CreateMatchWorldUseCase(rejectingLoader, matchWorlds)

        useCase.handle(MatchStarted(matchId = matchId, stageId = stageId, atTick = 1L))

        val saved = matchWorlds.findByMatch(matchId)
        assertThat(saved).isNotNull
        assertThat(saved!!.worldId).isEqualTo(defaultWorldId)
        // Placement was attempted, even though it failed.
        assertThat(rejectingLoader.placements).hasSize(1)
    }

    @Test
    fun `custom schematicPathFor and worldIdFor policies are honored`() {
        val customPath = Paths.get("stages", "${matchId.value}.schem")
        val customWorld = WorldId("arena-${matchId.value}")
        val loader = InMemorySchematicLoader(initial = mapOf(customPath to cannedSchematic.copy(path = customPath.toString())))
        val useCase = CreateMatchWorldUseCase(
            schematicLoader = loader,
            matchWorlds = matchWorlds,
            schematicPathFor = { _ -> customPath },
            worldIdFor = { _ -> customWorld },
        )

        useCase.handle(MatchStarted(matchId = matchId, stageId = stageId, atTick = 1L))

        val saved = matchWorlds.findByMatch(matchId)
        assertThat(saved).isNotNull
        assertThat(saved!!.worldId).isEqualTo(customWorld)
        assertThat(saved.sourceSchematicPath).isEqualTo(customPath.toString())
        assertThat(loader.placements().single().world).isEqualTo(customWorld)
    }

    @Test
    fun `defaultSchematicPath uses match-worlds dir and matchId-named schem file`() {
        assertThat(CreateMatchWorldUseCase.defaultSchematicPath(matchId))
            .isEqualTo(Paths.get("match-worlds", "${matchId.value}.schem"))
    }

    @Test
    fun `defaultWorldId prefixes match- to the matchId value`() {
        assertThat(CreateMatchWorldUseCase.defaultWorldId(matchId))
            .isEqualTo(WorldId("match-${matchId.value}"))
    }
}
