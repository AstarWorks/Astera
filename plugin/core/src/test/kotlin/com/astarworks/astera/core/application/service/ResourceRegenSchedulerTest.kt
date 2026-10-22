package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.contents.ItemContents
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.domain.event.ResourceRegenned
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.ResourceCap
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5k (ADR-0080) — [DefaultResourceRegenScheduler] contract.
 */
class ResourceRegenSchedulerTest {

    private lateinit var scheduler: FakeScheduler
    private lateinit var eventBus: InMemoryDomainEventBus
    private lateinit var itemRegistry: ItemContents
    private lateinit var resourcePool: InMemoryResourcePoolService
    private lateinit var regen: DefaultResourceRegenScheduler
    private val pid: PlayerId = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        scheduler = FakeScheduler()
        eventBus = InMemoryDomainEventBus()
        itemRegistry = ItemContents()
        resourcePool = InMemoryResourcePoolService(scheduler, eventBus, itemRegistry)
        regen = DefaultResourceRegenScheduler(resourcePool, eventBus)
    }

    @Test
    fun `tick with empty tracked set returns empty`() {
        assertThat(regen.tick(0L)).isEmpty()
    }

    @Test
    fun `tick regenerates energy at the type rate`() {
        resourcePool.initialiseFor(pid)
        // Deplete by half so regen has room.
        resourcePool.spendResource(pid, ResourceType.Energy, 50)
        regen.trackPlayer(pid)

        // Energy default regen = 15 / sec = 0.75 per tick: the second tick flushes 1.
        regen.tick(1L)
        regen.tick(2L)
        assertThat(resourcePool.currentPool(pid)!!.energy).isEqualTo(51)
    }

    @Test
    fun `tick accumulates sub-integer rates correctly`() {
        // Energy default regen = 15 / sec = 0.75 / tick. After 1 tick nothing flushed, after 4 ticks: +3.
        resourcePool.initialiseFor(pid)
        resourcePool.spendResource(pid, ResourceType.Energy, 50)
        regen.trackPlayer(pid)

        regen.tick(0L)
        assertThat(resourcePool.currentPool(pid)!!.energy).isEqualTo(50)

        repeat(3) { regen.tick((it + 1).toLong()) }
        assertThat(resourcePool.currentPool(pid)!!.energy).isEqualTo(53)
    }

    @Test
    fun `tick clamps at type cap`() {
        resourcePool.initialiseFor(pid)
        regen.trackPlayer(pid)

        repeat(100) { regen.tick(it.toLong()) }
        val cap = ResourceCap.DEFAULTS[ResourceType.Energy]!!.maxPool
        assertThat(resourcePool.currentPool(pid)!!.energy).isEqualTo(cap)
    }

    @Test
    fun `tick publishes ResourceRegenned on flush`() {
        val captured = mutableListOf<ResourceRegenned>()
        eventBus.subscribe(ResourceRegenned::class) { captured += it }
        resourcePool.initialiseFor(pid)
        resourcePool.spendResource(pid, ResourceType.Energy, 50)
        regen.trackPlayer(pid)

        regen.tick(1L)
        regen.tick(2L)

        // 0.75/tick: the second tick crosses 1.0 and flushes.
        assertThat(captured.any { it.type == ResourceType.Energy }).isTrue()
    }

    @Test
    fun `tick returns set of changed players`() {
        resourcePool.initialiseFor(pid)
        resourcePool.spendResource(pid, ResourceType.Energy, 50)
        regen.trackPlayer(pid)

        regen.tick(1L)
        val changed = regen.tick(2L)
        assertThat(changed).contains(pid)
    }

    @Test
    fun `tick skips zero-regen types`() {
        // Ammo regen = 0 / sec → never publishes ResourceRegenned for Ammo.
        val captured = mutableListOf<ResourceRegenned>()
        eventBus.subscribe(ResourceRegenned::class) { captured += it }
        resourcePool.initialiseFor(pid)
        resourcePool.spendResource(pid, ResourceType.Ammo, 10)
        regen.trackPlayer(pid)

        repeat(40) { regen.tick(it.toLong()) }

        assertThat(captured.none { it.type == ResourceType.Ammo }).isTrue()
    }

    @Test
    fun `untrackPlayer stops regen and clears accumulator`() {
        resourcePool.initialiseFor(pid)
        resourcePool.spendResource(pid, ResourceType.Energy, 50)
        regen.trackPlayer(pid)
        regen.tick(1L)
        regen.untrackPlayer(pid)

        repeat(100) { regen.tick(it.toLong() + 10L) }

        // Tracked for tick 1 only (0.75 accumulated, nothing flushed), then untracked.
        val poolAfter = resourcePool.currentPool(pid)!!
        // No further energy regen.
        assertThat(poolAfter.energy).isEqualTo(50)
    }

    @Test
    fun `trackedPlayers returns immutable snapshot`() {
        regen.trackPlayer(pid)
        val snapshot = regen.trackedPlayers()
        regen.untrackPlayer(pid)
        // Caller's snapshot is unaffected.
        assertThat(snapshot).contains(pid)
    }
}
