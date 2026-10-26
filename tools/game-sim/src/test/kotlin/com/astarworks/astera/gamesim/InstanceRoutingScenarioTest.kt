package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.*
import com.astarworks.astera.core.application.service.InstanceDirectory
import com.astarworks.astera.core.application.service.instance.GameInstance
import com.astarworks.astera.core.application.service.instance.GameInstanceRegistry
import com.astarworks.astera.core.application.service.instance.SlotAllocator
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.event.EventRoute
import com.astarworks.astera.core.domain.game.EntityRef
import com.astarworks.astera.core.domain.game.GameScope
import com.astarworks.astera.core.domain.game.InstanceId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.core.adapter.paper.scheduler.PaperComputePool
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration

/** Real host-neutral wiring, slot allocator, bus and compute adapters; no mocked services. */
class InstanceRoutingScenarioTest {
    private data class Probe(
        val sequence: Int,
        override val route: EventRoute,
        override val occurredAt: Instant = Instant.EPOCH,
        override val version: Int = 1,
    ) : DomainEvent

    @Test
    fun `two live matches isolate local subscriptions and preserve delivery ownership and FIFO`() {
        scenario("instance-routing-fifo") {
            val red = player("routing-red")
            val blue = player("routing-blue")
            val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
            val first = (lifecycle.start(StageId("example-arena")) as Result.Success).value
            val second = (lifecycle.start(StageId("example-arena")) as Result.Success).value
            val firstId = InstanceId(first.value)
            val secondId = InstanceId(second.value)
            lifecycle.entry(first, red.id, TeamId("red"))
            lifecycle.entry(second, blue.id, TeamId("blue"))
            advance(2)
            val resolver = sim.services.getOrThrow<IGameScopeResolver>()
            assertThat(resolver.resolve(EntityRef(red.id.uuid, WorldId("world")))).isEqualTo(GameScope.Instance(firstId))
            assertThat(resolver.resolve(EntityRef(blue.id.uuid, WorldId("world")))).isEqualTo(GameScope.Instance(secondId))

            val local = mutableListOf<Int>()
            val other = mutableListOf<Int>()
            val global = mutableListOf<Int>()
            val io = mutableListOf<Int>()
            val bus = sim.eventBus
            val subscriptions = listOf(
                bus.local(firstId).subscribe(Probe::class, EventDelivery.SameStrand) {
                    check(sim.scheduler.isOnScope(ExecutionScope.Instance(firstId)))
                    local += it.sequence
                },
                bus.local(secondId).subscribe(Probe::class, EventDelivery.SameStrand) { other += it.sequence },
                bus.subscribe(Probe::class, EventDelivery.Global) {
                    check(sim.scheduler.isOnScope(ExecutionScope.Global))
                    global += it.sequence
                },
                bus.subscribe(Probe::class, EventDelivery.Io) {
                    check(sim.scheduler.isOnScope(ExecutionScope.Io))
                    io += it.sequence
                },
            )
            sim.scheduler.run(ExecutionScope.Entity(red.id.uuid), Duration.ZERO) {
                bus.publish(Probe(1, EventRoute.Instance(firstId)))
                bus.publish(Probe(2, EventRoute.Player(red.id.uuid)))
                assertThat(local).isEmpty() // Never executes inline on the entity owner.
                assertThat(global).isEmpty()
            }
            sim.scheduler.advanceTicks(0)
            assertThat(local).containsExactly(1, 2)
            assertThat(other).isEmpty()
            assertThat(global).containsExactly(1, 2)
            assertThat(io).containsExactly(1, 2)

            // SameStrand is synchronous on its instance; migrated Global delivery is synchronous on Global.
            sim.scheduler.run(ExecutionScope.Instance(firstId), Duration.ZERO) {
                bus.local(firstId).publish(Probe(3, EventRoute.Instance(firstId)))
                assertThat(local).containsExactly(1, 2, 3)
            }
            sim.scheduler.advanceTicks(0)
            bus.publish(Probe(4, EventRoute.Global))
            assertThat(global).containsExactly(1, 2, 3, 4)
            assertThat(local).containsExactly(1, 2, 3)
            subscriptions.forEach { it.cancel() }
            bus.publish(Probe(5, EventRoute.Instance(firstId)))
            sim.scheduler.advanceTicks(0)
            assertThat(local).containsExactly(1, 2, 3)
        }
    }

    @Test
    fun `slot allocation readiness reset and free drive instance lifecycle and directory`() {
        scenario("slot-instance-lifecycle") {
            val player = player("slot-player")
            val instances = sim.services.getOrThrow<GameInstanceRegistry>()
            val directory = sim.services.getOrThrow<InstanceDirectory>()
            val resolver = sim.services.getOrThrow<IGameScopeResolver>()
            val entity = EntityRef(player.id.uuid, WorldId("survival"))
            assertThat(resolver.resolve(entity)).isEqualTo(GameScope.OpenWorld(entity.world))
            val beforeBootstrap = instances.snapshot().size
            val slots = SlotAllocator(instances, columns = 2, rows = 1)
            assertThat(instances.snapshot()).hasSize(beforeBootstrap)
            slots.initializeLobby()
            val slot = checkNotNull(slots.allocate(StageId("example-arena")))
            val instance = checkNotNull(instances.find(slot.instanceId))
            assertThat(instance.state).isEqualTo(GameInstance.State.Allocating)
            sim.scheduler.advanceTicks(0)
            assertThat(instance.state).isEqualTo(GameInstance.State.Building)
            slots.state(slot.index, SlotAllocator.State.Ready)
            sim.scheduler.advanceTicks(0)
            assertThat(instance.state).isEqualTo(GameInstance.State.Ready)
            slots.state(slot.index, SlotAllocator.State.Running)
            directory.admit(player.id.uuid, instance.id)
            sim.scheduler.advanceTicks(0)
            assertThat(instance.state).isEqualTo(GameInstance.State.Running)
            assertThat(resolver.resolve(entity)).isEqualTo(GameScope.Instance(instance.id))
            sim.scheduler.run(ExecutionScope.Entity(player.id.uuid), Duration.ZERO) {
                assertThatThrownBy { directory.remove(player.id.uuid) }.isInstanceOf(IllegalStateException::class.java)
            }
            instances.draining(instance.id)
            sim.scheduler.advanceTicks(0)
            assertThat(instance.state).isEqualTo(GameInstance.State.Draining)
            slots.state(slot.index, SlotAllocator.State.Resetting)
            sim.scheduler.advanceTicks(0)
            assertThat(instance.state).isEqualTo(GameInstance.State.Resetting)
            slots.free(slot.index)
            assertThat(directory.find(player.id.uuid)).isNull()
            sim.scheduler.advanceTicks(0)
            assertThat(instance.state).isEqualTo(GameInstance.State.Free)
            assertThat(instances.find(instance.id)).isNull()
            assertThat(resolver.resolve(entity)).isEqualTo(GameScope.OpenWorld(entity.world))
            val reused = checkNotNull(slots.allocate(StageId("example-arena")))
            assertThat(reused.index).isEqualTo(slot.index)
            assertThat(reused.instanceId).isNotEqualTo(slot.instanceId)
            slots.state(reused.index, SlotAllocator.State.Resetting)
            val repasted = slots.reallocate(reused.index)
            slots.state(repasted.index, SlotAllocator.State.Ready)
            sim.scheduler.advanceTicks(0)
            assertThat(instances.find(reused.instanceId)).isNull()
            assertThat(instances.find(repasted.instanceId)?.state).isEqualTo(GameInstance.State.Ready)
            assertThat(repasted.instanceId).isNotEqualTo(reused.instanceId)
        }
    }

    @Test
    fun `inline and bounded compute pools merge fixed chunks identically at different worker counts`() {
        scenario("compute-pool-determinism") {
            val caster = player("compute-caster")
            val inline = sim.services.getOrThrow<IComputePool>()
            val seed = castSeed(1234L, caster.id.uuid, 7L)
            val chunks = fixedChunks(23, 4).map { range ->
                { range.map { kotlin.random.Random(seed xor it.toLong()).nextLong() } }
            }
            val expected = inline.forkJoin(chunks)
            PaperComputePool(1).use { pool ->
                assertThat(pool.forkJoin(chunks)).isEqualTo(expected)
                assertThat(pool.async(WorkClass.Combat) { seed }.join()).isEqualTo(seed)
            }
            PaperComputePool(3).use { pool ->
                assertThat(pool.forkJoin(chunks)).isEqualTo(expected)
                val active = java.util.concurrent.atomic.AtomicInteger()
                val overlap = java.util.concurrent.atomic.AtomicBoolean()
                val done = java.util.concurrent.CountDownLatch(128)
                val order = mutableListOf<Int>()
                val strand = com.astarworks.astera.core.application.service.SerialExecutor(java.util.concurrent.Executor { task ->
                    pool.async(WorkClass.Background) { task.run() }
                })
                repeat(128) { index -> strand.execute {
                    if (active.incrementAndGet() != 1) overlap.set(true)
                    order += index
                    active.decrementAndGet()
                    done.countDown()
                } }
                assertThat(done.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
                assertThat(overlap.get()).isFalse()
                assertThat(order).containsExactlyElementsOf(0 until 128)
            }
            assertThat(inline.async(WorkClass.Background) { seed }.join()).isEqualTo(seed)
            assertThat(castSeed(1234L, caster.id.uuid, 8L)).isNotEqualTo(seed)
            assertThat(inline.forkJoin(emptyList<() -> Int>())).isEmpty()
        }
    }
}
