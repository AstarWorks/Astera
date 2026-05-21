package com.astarworks.astera.core.application.service.afk

import com.astarworks.astera.core.domain.afk.ActivityKind
import com.astarworks.astera.core.domain.event.PlayerAfkKicked
import com.astarworks.astera.core.domain.event.PlayerAfkWarned
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class AfkSweepSchedulerTest {

    private val player = PlayerId(UUID.randomUUID())

    @Test
    fun `crossing warning threshold publishes PlayerAfkWarned once`() {
        val now = LongRef(0L)
        val detector = InMemoryAfkDetector(tickProvider = { now.value })
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler()
        val sweeper = AfkSweepScheduler(
            detector = detector,
            eventBus = bus,
            scheduler = scheduler,
            tickProvider = { now.value },
            playerSnapshot = { listOf(player) },
        )
        detector.recordActivity(player, ActivityKind.Movement, atTick = 0L)

        now.value = 700 // > 30s
        sweeper.sweepOnce()
        sweeper.sweepOnce() // dedupe — should not double-publish

        val warned = bus.publishedOfType<PlayerAfkWarned>()
        assertThat(warned).hasSize(1)
        assertThat(warned.single().playerId).isEqualTo(player)
    }

    @Test
    fun `crossing kick threshold publishes PlayerAfkKicked and resets detector`() {
        val now = LongRef(0L)
        val detector = InMemoryAfkDetector(tickProvider = { now.value })
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler()
        val sweeper = AfkSweepScheduler(
            detector = detector,
            eventBus = bus,
            scheduler = scheduler,
            tickProvider = { now.value },
            playerSnapshot = { listOf(player) },
        )
        detector.recordActivity(player, ActivityKind.Movement, atTick = 0L)

        now.value = 700
        sweeper.sweepOnce() // Warning
        now.value = 2500
        sweeper.sweepOnce() // Afk

        assertThat(bus.publishedOfType<PlayerAfkKicked>()).hasSize(1)
        // After kick, the detector should treat the player as untracked again.
        assertThat(detector.snapshot()).doesNotContainKey(player)
    }

    private class LongRef(var value: Long)
}
