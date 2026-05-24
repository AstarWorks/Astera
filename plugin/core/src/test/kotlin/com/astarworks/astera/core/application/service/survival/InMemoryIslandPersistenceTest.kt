package com.astarworks.astera.core.application.service.survival

import com.astarworks.astera.core.application.service.InMemorySchematicLoader
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InMemoryIslandPersistenceTest {

    private val islandId = IslandId("test-island-1")
    private val world = WorldId("survival-1")
    private val schematicLoader = InMemorySchematicLoader()
    private val persistence = InMemoryIslandPersistence(schematicLoader)

    @Test
    fun `save mints a deterministic Schematic`() {
        val result = persistence.save(islandId, world)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        val schematic = (result as Result.Success).value
        assertThat(schematic.path).contains(islandId.value)
    }

    @Test
    fun `exists is false before save and true after`() {
        assertThat(persistence.exists(islandId)).isFalse
        persistence.save(islandId, world)
        assertThat(persistence.exists(islandId)).isTrue
    }

    @Test
    fun `load returns previously saved snapshot`() {
        persistence.save(islandId, world)
        val result = persistence.load(islandId)
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `load before save returns NotFound`() {
        val result = persistence.load(IslandId("never-saved"))
        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `place after save delegates to schematic loader`() {
        persistence.save(islandId, world)
        val result = persistence.place(islandId, world, Vec3.ZERO)
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }
}
