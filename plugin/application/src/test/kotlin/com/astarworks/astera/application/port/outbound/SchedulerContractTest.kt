package com.astarworks.astera.application.port.outbound

import com.astarworks.astera.testfixtures.FakeScheduler
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SchedulerContractTest {

    @Test
    fun `runLater fires when virtual time reaches the target tick`() {
        val s = FakeScheduler()
        var called = 0
        s.runLater(1.seconds, block = { called++ })

        s.advanceTicks(19)
        assertThat(called).isEqualTo(0)

        s.advanceTicks(1) // total 20 ticks = 1s
        assertThat(called).isEqualTo(1)
    }

    @Test
    fun `runRepeating fires each period`() {
        val s = FakeScheduler()
        var n = 0
        s.runRepeating(initialDelay = 100.milliseconds, period = 50.milliseconds, block = { n++ })

        s.advanceTicks(2) // initial delay
        assertThat(n).isEqualTo(1)
        s.advanceTicks(1) // 1 period
        assertThat(n).isEqualTo(2)
        s.advanceTicks(3) // 3 more periods
        assertThat(n).isEqualTo(5)
    }

    @Test
    fun `cancelled task does not fire`() {
        val s = FakeScheduler()
        var called = false
        val handle = s.runLater(1.seconds) { called = true }
        handle.cancel()
        s.advanceTicks(100)
        assertThat(called).isFalse()
        assertThat(handle.isActive).isFalse()
    }

    @Test
    fun `runLaterTicks extension converts to Duration`() {
        val s = FakeScheduler()
        var called = false
        s.runLaterTicks(10) { called = true }
        s.advanceTicks(9)
        assertThat(called).isFalse()
        s.advanceTicks(1)
        assertThat(called).isTrue()
    }

    @Test
    fun `ticks helper produces the expected Duration`() {
        // 20 ticks * 50ms = 1000ms = 1s
        assertThat(ticks(20)).isEqualTo(1.seconds)
        assertThat(ticks(0)).isEqualTo(0.milliseconds)
    }

    @Test
    fun `awaitTicks 0 returns immediately`() = runBlocking {
        val s = FakeScheduler()
        s.awaitTicks(0) // must not suspend forever
    }

    @Test
    fun `awaitTicks resumes after advanceTicks reaches the target`() = runBlocking {
        val s = FakeScheduler()
        val deferred = async(s.mainDispatcher) {
            s.awaitTicks(5)
            "resumed"
        }
        // The coroutine is now suspended waiting on the FakeScheduler queue.
        s.advanceTicks(5)
        val result = deferred.await()
        assertThat(result).isEqualTo("resumed")
        assertThat(s.currentTick()).isEqualTo(5)
    }
}
