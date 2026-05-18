package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.barrier.BarrierId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.match.domain.barrier.Barrier
import com.astarworks.astera.match.domain.barrier.BarrierStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryBarrierGatewayTest {

    @Test
    fun `spawn registers the barrier and defaults its status to Active`() {
        val gateway = InMemoryBarrierGateway()
        val barrier = barrier(id = "b1")

        gateway.spawn(barrier)

        assertThat(gateway.all()).containsExactly(barrier)
        assertThat(gateway.statusOf(barrier.id)).isEqualTo(BarrierStatus.Active)
    }

    @Test
    fun `updateStatus mutates an existing barrier's status`() {
        val gateway = InMemoryBarrierGateway()
        val barrier = barrier(id = "b1")
        gateway.spawn(barrier)

        gateway.updateStatus(barrier.id, BarrierStatus.Disabled)

        assertThat(gateway.statusOf(barrier.id)).isEqualTo(BarrierStatus.Disabled)
        // The barrier itself is unchanged — status is tracked alongside, not on the aggregate.
        assertThat(gateway.all()).containsExactly(barrier)
    }

    @Test
    fun `despawn removes both the barrier and its status entry`() {
        val gateway = InMemoryBarrierGateway()
        val barrier = barrier(id = "b1")
        gateway.spawn(barrier)

        gateway.despawn(barrier.id)

        assertThat(gateway.all()).isEmpty()
        assertThat(gateway.statusOf(barrier.id)).isNull()
    }

    @Test
    fun `updateStatus on an unknown id is a no-op`() {
        val gateway = InMemoryBarrierGateway()
        val unknown = BarrierId("ghost")

        gateway.updateStatus(unknown, BarrierStatus.Destroyed)

        assertThat(gateway.statusOf(unknown)).isNull()
        assertThat(gateway.all()).isEmpty()
    }

    @Test
    fun `despawn on an unknown id is a no-op and does not throw`() {
        val gateway = InMemoryBarrierGateway()

        // Should simply do nothing; absence of exception is the assertion.
        gateway.despawn(BarrierId("ghost"))

        assertThat(gateway.all()).isEmpty()
    }

    @Test
    fun `spawn replaces an existing barrier with the same id`() {
        val gateway = InMemoryBarrierGateway()
        val original = barrier(id = "b1", slowdownPercent = 0.25)
        val replacement = barrier(id = "b1", slowdownPercent = 0.75)

        gateway.spawn(original)
        gateway.updateStatus(original.id, BarrierStatus.Disabled)
        gateway.spawn(replacement)

        assertThat(gateway.all()).containsExactly(replacement)
        // Re-spawn resets status to Active (fresh barrier semantics).
        assertThat(gateway.statusOf(replacement.id)).isEqualTo(BarrierStatus.Active)
    }

    private fun barrier(
        id: String,
        slowdownPercent: Double = 0.25,
    ): Barrier = Barrier(
        id = BarrierId(id),
        region = Region(
            min = Vec3(0.0, 64.0, 0.0),
            max = Vec3(4.0, 68.0, 4.0),
        ),
        origin = Vec3(2.0, 64.0, 2.0),
        creator = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
        createdAtTick = 0L,
        slowdownPercent = slowdownPercent,
        detectionEnabled = true,
    )
}
