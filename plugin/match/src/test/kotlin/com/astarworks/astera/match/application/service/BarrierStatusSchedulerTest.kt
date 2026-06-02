package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.barrier.BarrierId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.match.domain.barrier.Barrier
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class BarrierStatusSchedulerTest {

    @Test
    fun `start returns an active task handle that can be cancelled`() {
        val gateway = InMemoryBarrierGateway()
        val scheduler = FakeScheduler()
        val sut = BarrierStatusScheduler(gateway, scheduler)

        val handle = sut.start()

        assertThat(handle.isActive).isTrue()
        handle.cancel()
        assertThat(handle.isActive).isFalse()
    }

    @Test
    fun `heartbeat is a no-op against gateway state in Wave 5d`() {
        // Wave 5d: scheduler exists only to reserve the per-tick entry point.
        // This test pins the no-op contract so a future wave that adds
        // behaviour replaces it deliberately (and the test author has to
        // update the assertion explicitly rather than silently breaking).
        val gateway = InMemoryBarrierGateway()
        val scheduler = FakeScheduler()
        val barrier = barrier(id = "b1")
        gateway.spawn(barrier)
        val sut = BarrierStatusScheduler(gateway, scheduler)
        val handle = sut.start()

        scheduler.advanceTicks(20L * 10L) // 10 heartbeats at the default period.

        // Gateway state is unchanged after 10 heartbeats.
        assertThat(gateway.all()).containsExactly(barrier)
        handle.cancel()
    }

    @Test
    fun `cancelled handle stops further heartbeats`() {
        val gateway = InMemoryBarrierGateway()
        val scheduler = FakeScheduler()
        val sut = BarrierStatusScheduler(gateway, scheduler, periodTicks = 5L)
        val handle = sut.start()

        handle.cancel()
        scheduler.advanceTicks(100L)

        // No way to observe "no-op happened N times" given the gateway is
        // unchanged either way, but `isActive` is the contract callers
        // depend on for `AsteraMatchPlugin.onDisable` shutdown sequencing.
        assertThat(handle.isActive).isFalse()
    }

    private fun barrier(id: String): Barrier = Barrier(
        id = BarrierId(id),
        worldId = TEST_WORLD_ID,
        region = Region(
            min = Vec3(0.0, 64.0, 0.0),
            max = Vec3(4.0, 68.0, 4.0),
        ),
        origin = Vec3(2.0, 64.0, 2.0),
        creator = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
        createdAtTick = 0L,
        slowdownPercent = 0.25,
        detectionEnabled = true,
    )

    private companion object {
        private val TEST_WORLD_ID = com.astarworks.astera.core.domain.world.WorldId(
            "00000000-0000-0000-0000-0000000000aa",
        )
    }
}
