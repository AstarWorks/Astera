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
    fun `tick regenerates stamina at the type rate`() {
        resourcePool.initialiseFor(pid)
        // Deplete by half so regen has room.
        resourcePool.spendResource(pid, ResourceType.Stamina, 50)
        regen.trackPlayer(pid)

        // Stamina default regen = 20 / sec = 1 per tick.
        regen.tick(1L)
        assertThat(resourcePool.currentPool(pid)!!.stamina).isEqualTo(51)
    }

    @Test
    fun `tick accumulates sub-integer rates correctly`() {
        // Mana default regen = 5 / sec = 0.25 / tick. After 4 ticks: +1.
        resourcePool.initialiseFor(pid)
        resourcePool.spendResource(pid, ResourceType.Mana, 50)
        regen.trackPlayer(pid)

        repeat(3) { regen.tick(it.toLong()) }
        assertThat(resourcePool.currentPool(pid)!!.mana).isEqualTo(50)

        regen.tick(3L)
        assertThat(resourcePool.currentPool(pid)!!.mana).isEqualTo(51)
    }

    @Test
    fun `tick clamps at type cap`() {
        resourcePool.initialiseFor(pid)
        regen.trackPlayer(pid)

        repeat(100) { regen.tick(it.toLong()) }
        val cap = ResourceCap.DEFAULTS[ResourceType.Stamina]!!.maxPool
        assertThat(resourcePool.currentPool(pid)!!.stamina).isEqualTo(cap)
    }

    @Test
    fun `tick publishes ResourceRegenned on flush`() {
        val captured = mutableListOf<ResourceRegenned>()
        eventBus.subscribe(ResourceRegenned::class) { captured += it }
        resourcePool.initialiseFor(pid)
        resourcePool.spendResource(pid, ResourceType.Stamina, 50)
        regen.trackPlayer(pid)

        regen.tick(1L)

        // Stamina regen flushes (1/tick), Mana regen doesn't flush yet (0.25/tick).
        assertThat(captured.any { it.type == ResourceType.Stamina }).isTrue()
    }

    @Test
    fun `tick returns set of changed players`() {
        resourcePool.initialiseFor(pid)
        resourcePool.spendResource(pid, ResourceType.Stamina, 50)
        regen.trackPlayer(pid)

        val changed = regen.tick(1L)
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
        resourcePool.spendResource(pid, ResourceType.Mana, 50)
        regen.trackPlayer(pid)
        regen.tick(1L)
        regen.untrackPlayer(pid)

        repeat(100) { regen.tick(it.toLong() + 10L) }

        // Stamina was tracked in tick 1 only; pool not advanced past tick 1's flush.
        val poolAfter = resourcePool.currentPool(pid)!!
        // No further mana regen.
        assertThat(poolAfter.mana).isEqualTo(50)
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
