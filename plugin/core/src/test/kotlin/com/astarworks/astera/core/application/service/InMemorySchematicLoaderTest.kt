package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.SchematicLoadError
import com.astarworks.astera.core.application.port.outbound.SchematicPlaceError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.Schematic
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class InMemorySchematicLoaderTest {

    private val arenaPath = Paths.get("test-arena.schem")
    private val missingPath = Paths.get("does-not-exist.schem")
    private val arena = Schematic(arenaPath.toString(), size = Vec3(16.0, 8.0, 16.0), version = 2)
    private val world = WorldId("arena-1")
    private val unknownWorld = WorldId("nowhere")
    private val origin = Vec3(0.0, 64.0, 0.0)

    // -------------------------------------------------------------------------
    // load
    // -------------------------------------------------------------------------

    @Test
    fun `load returns Success with the canned schematic for a known path`() {
        val loader = InMemorySchematicLoader(initial = mapOf(arenaPath to arena))

        val result = loader.load(arenaPath)

        assertThat(result).isEqualTo(Result.Success(arena))
    }

    @Test
    fun `load returns Failure NotFound for an unknown path`() {
        val loader = InMemorySchematicLoader(initial = mapOf(arenaPath to arena))

        val result = loader.load(missingPath)

        assertThat(result).isEqualTo(Result.Failure(SchematicLoadError.NotFound(missingPath)))
    }

    // -------------------------------------------------------------------------
    // place — acceptAnyWorld = true (default when no knownWorlds given)
    // -------------------------------------------------------------------------

    @Test
    fun `place returns Success and records placement when acceptAnyWorld is true`() {
        val loader = InMemorySchematicLoader(initial = mapOf(arenaPath to arena))

        val result = loader.place(arena, world, origin)

        assertThat(result).isEqualTo(Result.Success(Unit))
        assertThat(loader.placements()).containsExactly(
            InMemorySchematicLoader.Placement(arena, world, origin),
        )
    }

    // -------------------------------------------------------------------------
    // place — acceptAnyWorld = false (driven by knownWorlds set)
    // -------------------------------------------------------------------------

    @Test
    fun `place returns Failure UnknownWorld when world is not registered and acceptAnyWorld is false`() {
        val loader = InMemorySchematicLoader(
            initial = mapOf(arenaPath to arena),
            knownWorlds = setOf(world),
        )

        val result = loader.place(arena, unknownWorld, origin)

        assertThat(result).isEqualTo(Result.Failure(SchematicPlaceError.UnknownWorld(unknownWorld)))
        // No placement should have been recorded on failure.
        assertThat(loader.placements()).isEmpty()
    }

    @Test
    fun `place returns Success and records placement for a known world when acceptAnyWorld is false`() {
        val loader = InMemorySchematicLoader(
            initial = mapOf(arenaPath to arena),
            knownWorlds = setOf(world),
        )

        val result = loader.place(arena, world, origin)

        assertThat(result).isEqualTo(Result.Success(Unit))
        assertThat(loader.placements()).containsExactly(
            InMemorySchematicLoader.Placement(arena, world, origin),
        )
    }

    // -------------------------------------------------------------------------
    // register / unregister (schematics)
    // -------------------------------------------------------------------------

    @Test
    fun `register adds and unregister removes a schematic at runtime`() {
        val loader = InMemorySchematicLoader()

        // Before register: NotFound.
        assertThat(loader.load(arenaPath))
            .isEqualTo(Result.Failure(SchematicLoadError.NotFound(arenaPath)))

        loader.register(arenaPath, arena)
        assertThat(loader.load(arenaPath)).isEqualTo(Result.Success(arena))

        loader.unregister(arenaPath)
        assertThat(loader.load(arenaPath))
            .isEqualTo(Result.Failure(SchematicLoadError.NotFound(arenaPath)))
    }

    // -------------------------------------------------------------------------
    // registerWorld / unregisterWorld
    // -------------------------------------------------------------------------

    @Test
    fun `registerWorld and unregisterWorld toggle place acceptance when acceptAnyWorld is false`() {
        val loader = InMemorySchematicLoader(
            initial = mapOf(arenaPath to arena),
            knownWorlds = emptySet(),
            // Explicit acceptAnyWorld = false so an empty knownWorlds rejects everything
            // (otherwise the constructor default would treat empty as "accept everything").
            acceptAnyWorld = false,
        )

        // Initially rejected — no worlds known.
        assertThat(loader.place(arena, world, origin))
            .isEqualTo(Result.Failure(SchematicPlaceError.UnknownWorld(world)))
        assertThat(loader.placements()).isEmpty()

        loader.registerWorld(world)
        assertThat(loader.place(arena, world, origin)).isEqualTo(Result.Success(Unit))
        assertThat(loader.placements()).containsExactly(
            InMemorySchematicLoader.Placement(arena, world, origin),
        )

        loader.unregisterWorld(world)
        assertThat(loader.place(arena, world, origin))
            .isEqualTo(Result.Failure(SchematicPlaceError.UnknownWorld(world)))
        // The earlier successful placement remains in history; the failed call did not append.
        assertThat(loader.placements()).hasSize(1)
    }
}
