package com.astarworks.astera.core.application.service.entity

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityCategory
import com.astarworks.astera.core.domain.entity.HologramKind
import com.astarworks.astera.core.domain.entity.SpawnError
import com.astarworks.astera.core.domain.entity.SpawnSpec
import com.astarworks.astera.core.domain.event.EntityDespawned
import com.astarworks.astera.core.domain.event.EntitySpawned
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class RouterEntitySpawnerTest {

    private val world = WorldId("test")
    private val origin = Vec3(0.0, 64.0, 0.0)
    private val player = PlayerId(UUID.randomUUID())

    private fun fixtures(): Quintuple {
        val eventBus = FakeDomainEventBus()
        val scheduler = FakeScheduler(startTick = 100L)
        val minimal = MinimalNpcAdapter()
        val citizens = CitizensNpcAdapter() // skeleton: isAvailable=false
        val router = InMemoryNpcInteractionRouter()
        val registry = InMemoryEntityRegistry()
        // Citizens listed FIRST so the router prefers it when available
        // (mirrors ADR-0082's "Citizens > Minimal" priority order).
        val spawner = RouterEntitySpawner(
            npcAdapters = listOf(citizens, minimal),
            eventBus = eventBus,
            scheduler = scheduler,
            registry = registry,
            interactionRouter = router,
        )
        return Quintuple(spawner, eventBus, router, minimal, scheduler)
    }

    private data class Quintuple(
        val spawner: RouterEntitySpawner,
        val eventBus: FakeDomainEventBus,
        val router: InMemoryNpcInteractionRouter,
        val minimal: MinimalNpcAdapter,
        val scheduler: FakeScheduler,
    )

    @Test
    fun `armor-stand spawn falls through to minimal when citizens unavailable`() {
        val (spawner, _, _, minimal, _) = fixtures()
        val spec = SpawnSpec.ArmorStandNpc(displayName = MessageKey("astera.test.vendor"))

        val handle = (spawner.spawn(world, origin, spec) as Result.Success).value

        assertThat(handle.backendId).isEqualTo("minimal")
        assertThat(minimal.all()).contains(handle)
    }

    @Test
    fun `display NPC spawn publishes EntitySpawned with category NPC and current tick`() {
        val (spawner, eventBus, _, _, scheduler) = fixtures()
        val spec = SpawnSpec.DisplayNpc(kind = HologramKind.Text(MessageKey("astera.test.label")))

        spawner.spawn(world, origin, spec)

        val event = eventBus.published.filterIsInstance<EntitySpawned>().single()
        assertThat(event.category).isEqualTo(EntityCategory.NPC)
        assertThat(event.world).isEqualTo(world)
        assertThat(event.position).isEqualTo(origin)
        assertThat(event.atTick).isEqualTo(scheduler.currentTick())
    }

    @Test
    fun `citizens-only spec without citizens adapter returns NoSpawnableBackend`() {
        val (spawner, _, _, _, _) = fixtures()
        val spec = SpawnSpec.CitizensNpc(
            typeKey = "minecraft:villager",
            displayName = MessageKey("astera.test.guard"),
        )

        val result = spawner.spawn(world, origin, spec)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isEqualTo(SpawnError.NoSpawnableBackend)
    }

    @Test
    fun `mob spawn validates type key against the registry`() {
        val (spawner, _, _, _, _) = fixtures()
        val ok = spawner.spawn(world, origin, SpawnSpec.Mob(typeKey = "minecraft:zombie"))
        val unknown = spawner.spawn(world, origin, SpawnSpec.Mob(typeKey = "astera:not_a_real_mob"))

        assertThat(ok).isInstanceOf(Result.Success::class.java)
        assertThat(unknown).isInstanceOf(Result.Failure::class.java)
        assertThat((unknown as Result.Failure).error).isEqualTo(SpawnError.UnknownTypeKey)
    }

    @Test
    fun `projectile spawn assigns a router-owned backend tag`() {
        val (spawner, eventBus, _, _, _) = fixtures()
        val spec = SpawnSpec.Projectile(
            typeKey = "minecraft:arrow",
            velocity = Vec3(0.0, 0.0, 1.0),
            shooter = player,
        )

        val handle = (spawner.spawn(world, origin, spec) as Result.Success).value

        assertThat(handle.backendId).isEqualTo("router-projectile")
        val event = eventBus.published.filterIsInstance<EntitySpawned>().single()
        assertThat(event.category).isEqualTo(EntityCategory.PROJECTILE)
    }

    @Test
    fun `despawn publishes EntityDespawned with EXPLICIT reason and unbinds router intents`() {
        val (spawner, eventBus, router, _, _) = fixtures()
        val handle = (spawner.spawn(world, origin, SpawnSpec.ArmorStandNpc(null)) as Result.Success).value
        router.bindRightClick(handle, GuiIntent("loadout.edit", mapOf("slot" to "MAIN")))

        val result = spawner.despawn(handle)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val despawnEvent = eventBus.published.filterIsInstance<EntityDespawned>().single()
        assertThat(despawnEvent.handle).isEqualTo(handle)
        assertThat(despawnEvent.reason).isEqualTo(EntityDespawned.Reason.EXPLICIT)
        // Router unbound the handle alongside despawn.
        assertThat(router.all()).isEmpty()
    }

    @Test
    fun `despawn returns UnknownTypeKey for handles the router never minted`() {
        val (spawner, _, _, _, _) = fixtures()
        val foreign = com.astarworks.astera.core.domain.entity.EntityHandle(UUID.randomUUID(), "ghost")

        val result = spawner.despawn(foreign)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isEqualTo(SpawnError.UnknownTypeKey)
    }

    @Test
    fun `teleport updates router-owned mob positions silently`() {
        val (spawner, _, _, _, _) = fixtures()
        val handle = (spawner.spawn(world, origin, SpawnSpec.Mob("minecraft:zombie")) as Result.Success).value

        val result = spawner.teleport(handle, Vec3(10.0, 64.0, 10.0))

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `all enumerates handles across mob projectile and minimal NPC adapter`() {
        val (spawner, _, _, _, _) = fixtures()
        spawner.spawn(world, origin, SpawnSpec.Mob("minecraft:zombie"))
        spawner.spawn(world, origin, SpawnSpec.ArmorStandNpc(null))
        spawner.spawn(
            world,
            origin,
            SpawnSpec.Projectile("minecraft:arrow", Vec3(0.0, 0.0, 1.0), player),
        )

        assertThat(spawner.all()).hasSize(3)
    }
}
