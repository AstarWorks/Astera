package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.EgoError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.ego.EgoConfig
import com.astarworks.astera.core.domain.event.EgoGaugeChanged
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** ADR-0231: [InMemoryEgoGauge] contract. */
class InMemoryEgoGaugeTest {

    private val pid = PlayerId(UUID.randomUUID())
    private val scheduler = FakeScheduler()
    private val bus = FakeDomainEventBus()
    private val gauge = InMemoryEgoGauge(
        scheduler,
        bus,
        EgoConfig(max = 100, gainOnDealt = 5, gainOnTaken = 4, decayPerSecond = 10, decayDelayTicks = 100),
    )

    @Test
    fun `gain clamps at max and announces the fill exactly once`() {
        gauge.gain(pid, 95)
        assertThat(bus.publishedOfType<EgoGaugeChanged>().map { it.becameFull }).containsExactly(false)

        assertThat(gauge.gain(pid, 30)).isEqualTo(100)
        assertThat(gauge.gain(pid, 30)).isEqualTo(100)

        val events = bus.publishedOfType<EgoGaugeChanged>()
        assertThat(events.map { it.value }).containsExactly(95, 100)
        assertThat(events.map { it.becameFull }).containsExactly(false, true)
    }

    @Test
    fun `non-positive gain is ignored`() {
        assertThat(gauge.gain(pid, 0)).isZero()
        assertThat(gauge.gain(pid, -3)).isZero()
        assertThat(bus.publishedOfType<EgoGaugeChanged>()).isEmpty()
    }

    @Test
    fun `spend consumes, and refuses without touching the gauge when short`() {
        gauge.gain(pid, 60)

        val short = gauge.spend(pid, 100)
        assertThat((short as Result.Failure).error).isEqualTo(EgoError.Insufficient(available = 60, requested = 100))
        assertThat(gauge.value(pid)).isEqualTo(60)

        val ok = gauge.spend(pid, 25)
        assertThat((ok as Result.Success).value).isEqualTo(35)
        assertThat(gauge.value(pid)).isEqualTo(35)
        assertThat(gauge.spend(pid, -1)).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `reset empties the gauge`() {
        gauge.gain(pid, 50)
        gauge.reset(pid)
        assertThat(gauge.value(pid)).isZero()
        assertThat(bus.publishedOfType<EgoGaugeChanged>().last().value).isZero()
    }

    @Test
    fun `decay only starts after the out-of-combat delay`() {
        gauge.gain(pid, 50)

        scheduler.advanceTicks(99)
        gauge.decay(scheduler.currentTick())
        assertThat(gauge.value(pid)).isEqualTo(50)

        scheduler.advanceTicks(1)
        gauge.decay(scheduler.currentTick())
        assertThat(gauge.value(pid)).isEqualTo(40)

        scheduler.advanceTicks(20)
        gauge.decay(scheduler.currentTick())
        assertThat(gauge.value(pid)).isEqualTo(30)
    }

    @Test
    fun `a fresh gain restarts the out-of-combat timer`() {
        gauge.gain(pid, 50)
        scheduler.advanceTicks(90)
        gauge.gain(pid, 5)
        scheduler.advanceTicks(20)
        gauge.decay(scheduler.currentTick())
        assertThat(gauge.value(pid)).isEqualTo(55)
    }
}
