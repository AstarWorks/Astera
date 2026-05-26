package com.astarworks.astera.core.application.service.effect

import com.astarworks.astera.core.domain.effect.timer.CountdownTimerId
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InMemoryCountdownTimerTest {

    @Test
    fun `start fires onTick every tick and onComplete at zero`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val sut = InMemoryCountdownTimer(scheduler)

        val ticks = mutableListOf<Long>()
        var completed = false

        val handle = sut.start(
            CountdownTimerId("match-countdown"),
            durationTicks = 3L,
            onTick = { ticks += it },
            onComplete = { completed = true },
        )

        assertThat(handle.remainingTicks).isEqualTo(3L)
        scheduler.advanceTicks(3)
        assertThat(ticks).containsExactly(2L, 1L, 0L)
        assertThat(completed).isTrue()
        assertThat(handle.isActive).isFalse()
    }

    @Test
    fun `pause and resume preserve remaining`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val sut = InMemoryCountdownTimer(scheduler)

        val handle = sut.start(
            CountdownTimerId("cooldown"),
            durationTicks = 10L,
        )

        scheduler.advanceTicks(3)
        assertThat(handle.remainingTicks).isEqualTo(7L)
        handle.pause()
        scheduler.advanceTicks(10)
        assertThat(handle.remainingTicks).isEqualTo(7L)
        assertThat(handle.isPaused).isTrue()
        handle.resume()
        scheduler.advanceTicks(7)
        assertThat(handle.remainingTicks).isEqualTo(0L)
    }

    @Test
    fun `cancel does not fire onComplete`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val sut = InMemoryCountdownTimer(scheduler)
        var completed = false
        val handle = sut.start(
            CountdownTimerId("test"),
            durationTicks = 5L,
            onComplete = { completed = true },
        )
        scheduler.advanceTicks(2)
        handle.cancel()
        scheduler.advanceTicks(10)
        assertThat(completed).isFalse()
        assertThat(handle.isActive).isFalse()
    }

    @Test
    fun `restart with same id cancels existing`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val sut = InMemoryCountdownTimer(scheduler)
        val id = CountdownTimerId("x")
        val first = sut.start(id, durationTicks = 10L)
        scheduler.advanceTicks(3)
        val second = sut.start(id, durationTicks = 20L)
        assertThat(first.isActive).isFalse()
        assertThat(second.remainingTicks).isEqualTo(20L)
        assertThat(sut.find(id)).isSameAs(second)
    }

    @Test
    fun `pauseAll and resumeAll affect every active timer`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val sut = InMemoryCountdownTimer(scheduler)
        val a = sut.start(CountdownTimerId("a"), durationTicks = 10L)
        val b = sut.start(CountdownTimerId("b"), durationTicks = 10L)
        sut.pauseAll()
        scheduler.advanceTicks(5)
        assertThat(a.remainingTicks).isEqualTo(10L)
        assertThat(b.remainingTicks).isEqualTo(10L)
        sut.resumeAll()
        scheduler.advanceTicks(3)
        assertThat(a.remainingTicks).isEqualTo(7L)
        assertThat(b.remainingTicks).isEqualTo(7L)
    }
}
