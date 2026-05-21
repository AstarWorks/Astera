package com.astarworks.astera.core.application.service.mob

import com.astarworks.astera.core.application.contents.MobContents
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.entity.EntityCategory
import com.astarworks.astera.core.domain.event.MobSpawned
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.mob.MobBackendId
import com.astarworks.astera.core.domain.mob.MobError
import com.astarworks.astera.core.domain.mob.MobSpec
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MobSpawnerRouterTest {

    private val world = WorldId("test")
    private val pos = Vec3(0.0, 64.0, 0.0)

    private fun fixture(): RouterFixture {
        val bus = InMemoryDomainEventBus()
        val scheduler = FakeScheduler(startTick = 0L)
        val registry = MobContents()
        registry.register(specOf("astera:forest-wolf"))
        val minimal = MinimalMobAdapter(bus, scheduler)
        val mythic = MythicMobsMobAdapter()
        return RouterFixture(bus, scheduler, registry, minimal, mythic)
    }

    private fun specOf(id: String): MobSpec = MobSpec(
        id = MobTypeId(id),
        displayName = LocalizedText.EMPTY,
        baseEntityType = EntityCategory.MOB,
        attributes = SimpleAttributeContainer(),
    )

    private data class RouterFixture(
        val bus: InMemoryDomainEventBus,
        val scheduler: FakeScheduler,
        val registry: MobContents,
        val minimal: MinimalMobAdapter,
        val mythic: MythicMobsMobAdapter,
    )

    @Test
    fun `spawn fails with UnknownMobType when registry lookup misses`() {
        val f = fixture()
        val router = MobSpawnerRouter(
            adapters = listOf(f.mythic, f.minimal),
            registry = f.registry,
            eventBus = f.bus,
            scheduler = f.scheduler,
        )
        val result = router.spawn(world, pos, MobTypeId("astera:nobody"))
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(MobError.UnknownMobType)
    }

    @Test
    fun `spawn falls through MythicMobs skeleton to Minimal in Phase 1`() {
        val f = fixture()
        val router = MobSpawnerRouter(
            adapters = listOf(f.mythic, f.minimal), // mythic listed first
            registry = f.registry,
            eventBus = f.bus,
            scheduler = f.scheduler,
        )
        // MythicMobs reports unavailable → router picks Minimal.
        assertThat(router.activeBackendId()).isEqualTo(MobBackendId.MINIMAL)

        val captured = mutableListOf<MobSpawned>()
        f.bus.subscribe(MobSpawned::class) { captured += it }

        val result = router.spawn(world, pos, MobTypeId("astera:forest-wolf"))
        assertThat(result).isInstanceOf(Result.Success::class.java)
        val handle = (result as Result.Success).value
        assertThat(handle.backendId).isEqualTo(MobBackendId.MINIMAL.value)

        assertThat(captured).hasSize(1)
        with(captured.single()) {
            assertThat(mobTypeId).isEqualTo(MobTypeId("astera:forest-wolf"))
            assertThat(backendId).isEqualTo(MobBackendId.MINIMAL)
            assertThat(world).isEqualTo(world)
            assertThat(position).isEqualTo(pos)
        }
        assertThat(f.minimal.trackedHandles()).contains(handle)
    }

    @Test
    fun `spawn returns NoAdapterAvailable when every adapter reports unavailable`() {
        val f = fixture()
        // Only the MythicMobs skeleton (unavailable) registered.
        val router = MobSpawnerRouter(
            adapters = listOf(f.mythic),
            registry = f.registry,
            eventBus = f.bus,
            scheduler = f.scheduler,
        )
        val result = router.spawn(world, pos, MobTypeId("astera:forest-wolf"))
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(MobError.NoAdapterAvailable)
        assertThat(router.activeBackendId()).isNull()
    }

    @Test
    fun `despawn routes by backend id`() {
        val f = fixture()
        val router = MobSpawnerRouter(
            adapters = listOf(f.mythic, f.minimal),
            registry = f.registry,
            eventBus = f.bus,
            scheduler = f.scheduler,
        )
        val handle = (router.spawn(world, pos, MobTypeId("astera:forest-wolf"))
            as Result.Success).value

        val result = router.despawn(handle)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(f.minimal.trackedHandles()).doesNotContain(handle)
    }
}
