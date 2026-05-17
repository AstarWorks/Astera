package com.astarworks.astera.core.adapter.paper.scheduler

import com.astarworks.astera.core.application.port.outbound.SchedulerRegion
import com.astarworks.astera.core.application.port.outbound.TICK
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Tests for [FakeScheduler] — verifies that the heap dispatcher correctly
 * handles ordering, repeating tasks, cancellation, absolute-tick scheduling,
 * and region-aware overloads.
 *
 * PaperScheduler itself cannot be tested without a live Bukkit environment;
 * FakeScheduler shares the same dispatch logic (priority queue + mark-and-sweep)
 * so these tests validate the design used in both.
 */
class FakeSchedulerTest {

    private lateinit var scheduler: FakeScheduler

    @BeforeEach
    fun setUp() {
        scheduler = FakeScheduler(startTick = 0L)
    }

    // ---- ordering --------------------------------------------------------

    @Test
    fun `100 tasks with various delays execute in tick order`() {
        val executed = mutableListOf<Int>()
        for (i in 1..100) {
            scheduler.runLater((TICK * i)) { executed += i }
        }
        scheduler.advanceTicks(100)
        assertThat(executed).isSorted
        assertThat(executed).hasSize(100)
    }

    @Test
    fun `tasks scheduled for the same tick all execute`() {
        val fired = mutableListOf<String>()
        scheduler.runLater(TICK * 5) { fired += "a" }
        scheduler.runLater(TICK * 5) { fired += "b" }
        scheduler.runLater(TICK * 5) { fired += "c" }
        scheduler.advanceTicks(5)
        assertThat(fired).containsExactlyInAnyOrder("a", "b", "c")
    }

    // ---- runRepeating ----------------------------------------------------

    @Test
    fun `runRepeating with period 2 fires 5 times over 10 ticks`() {
        var count = 0
        scheduler.runRepeating(TICK * 2, TICK * 2) { count++ }
        scheduler.advanceTicks(10)
        assertThat(count).isEqualTo(5)
    }

    @Test
    fun `runRepeating respects initial delay before first fire`() {
        var count = 0
        scheduler.runRepeating(TICK * 4, TICK * 2) { count++ }
        scheduler.advanceTicks(3)
        assertThat(count).isEqualTo(0)
        scheduler.advanceTicks(1)
        assertThat(count).isEqualTo(1)
        scheduler.advanceTicks(2)
        assertThat(count).isEqualTo(2)
    }

    // ---- cancellation (mark-and-sweep) -----------------------------------

    @Test
    fun `cancel before fire prevents execution`() {
        var fired = false
        val handle = scheduler.runLater(TICK * 10) { fired = true }
        handle.cancel()
        scheduler.advanceTicks(20)
        assertThat(fired).isFalse()
    }

    @Test
    fun `cancel mid-repeating stops future fires`() {
        var count = 0
        val handle = scheduler.runRepeating(TICK * 1, TICK * 2) { count++ }
        scheduler.advanceTicks(5)   // fires at t=1, t=3, t=5 → count = 3
        val countAfterFive = count
        handle.cancel()
        scheduler.advanceTicks(10)  // no more fires
        assertThat(count).isEqualTo(countAfterFive)
    }

    @Test
    fun `cancelled handle isActive returns false`() {
        val handle = scheduler.runLater(TICK * 100) { }
        assertThat(handle.isActive).isTrue()
        handle.cancel()
        assertThat(handle.isActive).isFalse()
    }

    // ---- scheduleAt ------------------------------------------------------

    @Test
    fun `scheduleAt fires at the correct absolute tick`() {
        var fired = false
        scheduler.scheduleAt(42L) { fired = true }
        scheduler.advanceTicks(41)
        assertThat(fired).isFalse()
        scheduler.advanceTicks(1)
        assertThat(fired).isTrue()
    }

    @Test
    fun `scheduleAt in the past fires on next advanceTicks`() {
        // Advance to tick 10 first, then schedule at tick 5 (already past).
        scheduler.advanceTicks(10)
        var fired = false
        scheduler.scheduleAt(5L) { fired = true }
        scheduler.advanceTicks(1)   // any advance should drain the overdue task
        assertThat(fired).isTrue()
    }

    @Test
    fun `scheduleAt cancel prevents execution`() {
        var fired = false
        val handle = scheduler.scheduleAt(50L) { fired = true }
        handle.cancel()
        scheduler.advanceTicks(100)
        assertThat(fired).isFalse()
    }

    // ---- region-aware overloads -----------------------------------------

    @Test
    fun `runLater with Global region returns active handle and fires`() {
        var fired = false
        val handle = scheduler.runLater(TICK * 3, SchedulerRegion.Global) { fired = true }
        assertThat(handle.isActive).isTrue()
        scheduler.advanceTicks(3)
        assertThat(fired).isTrue()
    }

    @Test
    fun `runLater with PerEntity region returns active handle and fires`() {
        var fired = false
        val region = SchedulerRegion.PerEntity(UUID.randomUUID())
        val handle = scheduler.runLater(TICK * 2, region) { fired = true }
        assertThat(handle.isActive).isTrue()
        scheduler.advanceTicks(2)
        assertThat(fired).isTrue()
    }

    @Test
    fun `runRepeating with region overload fires correctly`() {
        var count = 0
        scheduler.runRepeating(TICK * 1, TICK * 3, SchedulerRegion.Global) { count++ }
        scheduler.advanceTicks(10)
        // fires at t=1, t=4, t=7, t=10 → 4 times
        assertThat(count).isEqualTo(4)
    }

    @Test
    fun `scheduleAt with region overload fires at absolute tick`() {
        var fired = false
        scheduler.scheduleAt(7L, SchedulerRegion.Global) { fired = true }
        scheduler.advanceTicks(6)
        assertThat(fired).isFalse()
        scheduler.advanceTicks(1)
        assertThat(fired).isTrue()
    }

    // ---- currentTick advances with advanceTicks --------------------------

    @Test
    fun `currentTick reflects advanced time`() {
        assertThat(scheduler.currentTick()).isEqualTo(0L)
        scheduler.advanceTicks(15)
        assertThat(scheduler.currentTick()).isEqualTo(15L)
    }
}
