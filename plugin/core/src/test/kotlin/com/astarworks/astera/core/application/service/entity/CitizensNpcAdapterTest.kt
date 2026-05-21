package com.astarworks.astera.core.application.service.entity

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.HologramKind
import com.astarworks.astera.core.domain.entity.NpcBackendId
import com.astarworks.astera.core.domain.entity.NpcError
import com.astarworks.astera.core.domain.entity.NpcTraitSpec
import com.astarworks.astera.core.domain.entity.SpawnError
import com.astarworks.astera.core.domain.entity.SpawnSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CitizensNpcAdapterTest {

    private val world = WorldId("test")
    private val origin = Vec3(0.0, 64.0, 0.0)

    @Test
    fun `backendId is the canonical CITIZENS constant`() {
        val adapter = CitizensNpcAdapter()
        assertThat(adapter.backendId()).isEqualTo(NpcBackendId.CITIZENS)
        assertThat(adapter.backendId().value).isEqualTo("citizens")
    }

    @Test
    fun `skeleton reports unavailable until Wave 6b subclass overrides`() {
        val adapter = CitizensNpcAdapter()
        assertThat(adapter.isAvailable()).isFalse()
    }

    @Test
    fun `supports always returns false in the skeleton`() {
        val adapter = CitizensNpcAdapter()
        val variants = listOf(
            SpawnSpec.CitizensNpc(typeKey = "minecraft:villager", displayName = null),
            SpawnSpec.ArmorStandNpc(displayName = null),
            SpawnSpec.DisplayNpc(kind = HologramKind.Text(MessageKey("astera.test.label"))),
            SpawnSpec.Mob(typeKey = "minecraft:zombie"),
        )
        variants.forEach { assertThat(adapter.supports(it)).isFalse() }
    }

    @Test
    fun `spawn always fails with NoSpawnableBackend`() {
        val adapter = CitizensNpcAdapter()
        val spec = SpawnSpec.CitizensNpc(typeKey = "minecraft:villager", displayName = null)

        val result = adapter.spawn(world, origin, spec)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isEqualTo(SpawnError.NoSpawnableBackend)
    }

    @Test
    fun `applyTraits fails with BackendUnavailable`() {
        val adapter = CitizensNpcAdapter()
        val handle = com.astarworks.astera.core.domain.entity.EntityHandle(UUID.randomUUID(), "citizens")

        val result = adapter.applyTraits(handle, listOf(NpcTraitSpec.Lookable()))

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isEqualTo(NpcError.BackendUnavailable)
    }

    @Test
    fun `despawn discriminates between citizens-tagged and foreign handles`() {
        val adapter = CitizensNpcAdapter()
        val ours = com.astarworks.astera.core.domain.entity.EntityHandle(UUID.randomUUID(), "citizens")
        val foreign = com.astarworks.astera.core.domain.entity.EntityHandle(UUID.randomUUID(), "minimal")

        val oursResult = adapter.despawn(ours)
        val foreignResult = adapter.despawn(foreign)

        assertThat(oursResult).isInstanceOf(Result.Failure::class.java)
        assertThat((oursResult as Result.Failure).error).isEqualTo(NpcError.HandleNotFound)

        assertThat(foreignResult).isInstanceOf(Result.Failure::class.java)
        assertThat((foreignResult as Result.Failure).error).isEqualTo(NpcError.NotOwnedByAdapter)
    }

    @Test
    fun `subclass that overrides isAvailable participates in supports decisions`() {
        val available = object : CitizensNpcAdapter() {
            override fun isAvailable(): Boolean = true
        }
        assertThat(available.supports(SpawnSpec.CitizensNpc(typeKey = "minecraft:villager", displayName = null))).isTrue()
        assertThat(available.supports(SpawnSpec.ArmorStandNpc(null))).isTrue()
        assertThat(available.supports(SpawnSpec.Mob(typeKey = "minecraft:zombie"))).isFalse()
    }
}
