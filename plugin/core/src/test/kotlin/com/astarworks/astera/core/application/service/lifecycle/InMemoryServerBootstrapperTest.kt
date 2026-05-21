package com.astarworks.astera.core.application.service.lifecycle

import com.astarworks.astera.core.application.port.outbound.lifecycle.IServerLifecycleListener
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.lifecycle.ServerBooted
import com.astarworks.astera.core.domain.event.lifecycle.ServerReady
import com.astarworks.astera.core.domain.event.lifecycle.ServerShutdownCompleted
import com.astarworks.astera.core.domain.event.lifecycle.ServerShutdownStarted
import com.astarworks.astera.core.domain.lifecycle.LifecycleError
import com.astarworks.astera.core.domain.lifecycle.ShutdownPhase
import com.astarworks.astera.core.domain.lifecycle.ShutdownReason
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemoryServerBootstrapperTest {

    private class RecordingListener(
        override val priority: Int = 0,
        val name: String,
        val throwOnPhase: ShutdownPhase? = null,
    ) : IServerLifecycleListener {
        val log: MutableList<String> = mutableListOf()
        override fun onBoot() { log += "$name:boot" }
        override fun onReady() { log += "$name:ready" }
        override fun onShutdown(reason: ShutdownReason) { log += "$name:shutdown:${reasonLabel(reason)}" }
        override fun onShutdownPhase(phase: ShutdownPhase, reason: ShutdownReason) {
            log += "$name:phase:${phase.ordinalLabel}"
            if (phase == throwOnPhase) error("$name fails on $phase")
        }
        private fun reasonLabel(reason: ShutdownReason): String = when (reason) {
            ShutdownReason.Planned -> "planned"
            ShutdownReason.Restart -> "restart"
            ShutdownReason.Signal -> "signal"
            ShutdownReason.Crash -> "crash"
        }
    }

    @Test
    fun `boot fires listeners in priority order then registration order`() {
        val bus = FakeDomainEventBus()
        val sut = InMemoryServerBootstrapper(bus)
        val a = RecordingListener(priority = 0, name = "a")
        val b = RecordingListener(priority = 10, name = "b")
        val c = RecordingListener(priority = 0, name = "c")
        sut.register(a); sut.register(b); sut.register(c)

        val result = sut.boot()
        assertThat(result.isSuccess).isTrue
        val order = listOf(b.log.firstOrNull(), a.log.firstOrNull(), c.log.firstOrNull())
        assertThat(order).containsExactly("b:boot", "a:boot", "c:boot")
        assertThat(bus.published.filterIsInstance<ServerBooted>()).hasSize(1)
        assertThat(bus.published.filterIsInstance<ServerBooted>().first().pluginsBootedCount).isEqualTo(3)
    }

    @Test
    fun `markReady fires onReady on every listener and publishes ServerReady`() {
        val bus = FakeDomainEventBus()
        val now = Instant.parse("2026-05-20T00:00:00Z")
        val sut = InMemoryServerBootstrapper(bus, clock = { now })
        val a = RecordingListener(name = "a")
        sut.register(a)
        sut.boot()
        sut.markReady()
        assertThat(a.log).contains("a:ready")
        val readyEvents = bus.published.filterIsInstance<ServerReady>()
        assertThat(readyEvents).hasSize(1)
        assertThat(readyEvents.first().readyAt).isEqualTo(now)
    }

    @Test
    fun `markReady before boot returns NotReadyableYet`() {
        val bus = FakeDomainEventBus()
        val sut = InMemoryServerBootstrapper(bus)
        val result = sut.markReady()
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val err = (result as Result.Failure).error
        assertThat(err).isEqualTo(LifecycleError.NotReadyableYet)
    }

    @Test
    fun `shutdown walks the 5 phases in declared order for every listener`() {
        val bus = FakeDomainEventBus()
        val sut = InMemoryServerBootstrapper(bus)
        val a = RecordingListener(name = "a")
        sut.register(a)
        sut.boot()
        sut.shutdown(ShutdownReason.Planned)

        // Each phase invokes a in declared order; "a:shutdown:planned" first, then 5 phases.
        val expectedPhases = ShutdownPhase.ORDER.map { "a:phase:${it.ordinalLabel}" }
        assertThat(a.log).contains("a:shutdown:planned")
        assertThat(a.log.filter { it.startsWith("a:phase:") }).containsExactlyElementsOf(expectedPhases)
    }

    @Test
    fun `shutdown emits ServerShutdownStarted then ServerShutdownCompleted with executed phases`() {
        val bus = FakeDomainEventBus()
        val sut = InMemoryServerBootstrapper(bus)
        sut.register(RecordingListener(name = "only"))
        sut.boot()
        sut.shutdown(ShutdownReason.Signal)

        val started = bus.published.filterIsInstance<ServerShutdownStarted>()
        val completed = bus.published.filterIsInstance<ServerShutdownCompleted>()
        assertThat(started).hasSize(1)
        assertThat(started.first().reason).isEqualTo(ShutdownReason.Signal)
        assertThat(completed).hasSize(1)
        assertThat(completed.first().snapshot.phasesExecuted).containsExactlyElementsOf(ShutdownPhase.ORDER)
        // started occurs strictly before completed.
        val startIdx = bus.published.indexOfFirst { it is ServerShutdownStarted }
        val endIdx = bus.published.indexOfFirst { it is ServerShutdownCompleted }
        assertThat(startIdx).isLessThan(endIdx)
    }

    @Test
    fun `listener exception in one phase does not abort remaining phases`() {
        val bus = FakeDomainEventBus()
        val sut = InMemoryServerBootstrapper(bus)
        val a = RecordingListener(name = "a", throwOnPhase = ShutdownPhase.FlushRewardOutbox)
        val b = RecordingListener(name = "b")
        sut.register(a); sut.register(b)
        sut.boot()
        val result = sut.shutdown(ShutdownReason.Planned)
        assertThat(result.isSuccess).isTrue
        // All 5 phases still executed despite a throwing during FlushRewardOutbox.
        val executed = (result.getOrNull()!!).phasesExecuted
        assertThat(executed).containsExactlyElementsOf(ShutdownPhase.ORDER)
        // b reached CloseDatabase / DisablePlugins (later than a's throw).
        assertThat(b.log).contains("b:phase:close-database", "b:phase:disable-plugins")
        // Failure was recorded.
        assertThat(result.getOrNull()!!.listenerFailures).isNotEmpty
    }

    @Test
    fun `boot then boot returns AlreadyBooted`() {
        val bus = FakeDomainEventBus()
        val sut = InMemoryServerBootstrapper(bus)
        sut.boot()
        val again = sut.boot()
        assertThat(again).isInstanceOf(Result.Failure::class.java)
        assertThat((again as Result.Failure).error).isEqualTo(LifecycleError.AlreadyBooted)
    }

    @Test
    fun `shutdown before boot returns NotBooted`() {
        val bus = FakeDomainEventBus()
        val sut = InMemoryServerBootstrapper(bus)
        val result = sut.shutdown(ShutdownReason.Planned)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isEqualTo(LifecycleError.NotBooted)
    }

    @Test
    fun `unregister removes listener from fire order`() {
        val bus = FakeDomainEventBus()
        val sut = InMemoryServerBootstrapper(bus)
        val a = RecordingListener(name = "a")
        val reg = sut.register(a)
        assertThat(sut.listenersInFireOrder()).hasSize(1)
        reg.unregister()
        assertThat(sut.listenersInFireOrder()).isEmpty()
        // boot afterwards is fine; a not invoked.
        sut.boot()
        assertThat(a.log).isEmpty()
    }
}
