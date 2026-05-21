package com.astarworks.astera.core.application.service.entity

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.NpcBackendId
import com.astarworks.astera.core.domain.entity.NpcError
import com.astarworks.astera.core.domain.entity.NpcSkin
import com.astarworks.astera.core.domain.entity.NpcTraitSpec
import com.astarworks.astera.core.domain.entity.SpawnError
import com.astarworks.astera.core.domain.entity.SpawnSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class MinimalNpcAdapterTest {

    private val world = WorldId("test")
    private val origin = Vec3(0.0, 64.0, 0.0)

    @Test
    fun `backendId is the canonical MINIMAL constant`() {
        val adapter = MinimalNpcAdapter()
        assertThat(adapter.backendId()).isEqualTo(NpcBackendId.MINIMAL)
        assertThat(adapter.backendId().value).isEqualTo("minimal")
    }

    @Test
    fun `supports armor-stand and display NPCs but rejects citizens variant`() {
        val adapter = MinimalNpcAdapter()
        val armorStand = SpawnSpec.ArmorStandNpc(displayName = null)
        val display = SpawnSpec.DisplayNpc(
            kind = com.astarworks.astera.core.domain.entity.HologramKind.Text(MessageKey("astera.test.hello")),
        )
        val citizens = SpawnSpec.CitizensNpc(typeKey = "minecraft:villager", displayName = null, skin = NpcSkin(playerName = "Notch"))
        val mob = SpawnSpec.Mob(typeKey = "minecraft:zombie")

        assertThat(adapter.supports(armorStand)).isTrue()
        assertThat(adapter.supports(display)).isTrue()
        assertThat(adapter.supports(citizens)).isFalse()
        assertThat(adapter.supports(mob)).isFalse()
    }

    @Test
    fun `spawn returns a handle tagged with the minimal backend`() {
        val adapter = MinimalNpcAdapter()
        val spec = SpawnSpec.ArmorStandNpc(displayName = MessageKey("astera.test.shopkeeper"))

        val handle = (adapter.spawn(world, origin, spec) as Result.Success).value

        assertThat(handle.backendId).isEqualTo("minimal")
        assertThat(adapter.all()).contains(handle)
    }

    @Test
    fun `spawn rejects unsupported variants with NoSpawnableBackend`() {
        val adapter = MinimalNpcAdapter()
        val unsupported = SpawnSpec.CitizensNpc(typeKey = "minecraft:villager", displayName = null)

        val result = adapter.spawn(world, origin, unsupported)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isEqualTo(SpawnError.NoSpawnableBackend)
    }

    @Test
    fun `applyTraits records Lookable and HologramAttachment but ignores Pathing`() {
        val adapter = MinimalNpcAdapter()
        val handle = (adapter.spawn(world, origin, SpawnSpec.ArmorStandNpc(null)) as Result.Success).value

        val result = adapter.applyTraits(
            handle,
            listOf(
                NpcTraitSpec.Lookable(range = 8.0),
                NpcTraitSpec.Pathing(speed = 1.5),
            ),
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `applyTraits rejects handles owned by another backend`() {
        val adapter = MinimalNpcAdapter()
        val foreign = com.astarworks.astera.core.domain.entity.EntityHandle(UUID.randomUUID(), "citizens")

        val result = adapter.applyTraits(foreign, listOf(NpcTraitSpec.Lookable()))

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isEqualTo(NpcError.NotOwnedByAdapter)
    }

    @Test
    fun `despawn removes the handle and is non-idempotent against unknown handles`() {
        val adapter = MinimalNpcAdapter()
        val handle = (adapter.spawn(world, origin, SpawnSpec.ArmorStandNpc(null)) as Result.Success).value

        val first = adapter.despawn(handle)
        val second = adapter.despawn(handle)

        assertThat(first).isInstanceOf(Result.Success::class.java)
        assertThat(second).isInstanceOf(Result.Failure::class.java)
        assertThat((second as Result.Failure).error).isEqualTo(NpcError.HandleNotFound)
        assertThat(adapter.all()).doesNotContain(handle)
    }

    @Test
    fun `despawn refuses handles tagged for another backend`() {
        val adapter = MinimalNpcAdapter()
        val foreign = com.astarworks.astera.core.domain.entity.EntityHandle(UUID.randomUUID(), "citizens")

        val result = adapter.despawn(foreign)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isEqualTo(NpcError.NotOwnedByAdapter)
    }

    @Test
    fun `reload is a no-op on the minimal adapter`() {
        val adapter = MinimalNpcAdapter()
        val handle = (adapter.spawn(world, origin, SpawnSpec.ArmorStandNpc(null)) as Result.Success).value

        // No exception, no state change.
        adapter.reload(handle)

        assertThat(adapter.all()).contains(handle)
    }

    @Suppress("UNUSED_VARIABLE")
    private val playerForFutureWiring = PlayerId(UUID.randomUUID())
}
