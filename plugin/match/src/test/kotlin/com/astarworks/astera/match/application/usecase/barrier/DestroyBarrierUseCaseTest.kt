package com.astarworks.astera.match.application.usecase.barrier

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.barrier.BarrierId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.match.application.service.InMemoryBarrierGateway
import com.astarworks.astera.match.domain.barrier.Barrier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class DestroyBarrierUseCaseTest {

    @Test
    fun `known id is removed from the gateway and returns Success`() {
        val gateway = InMemoryBarrierGateway()
        val barrier = barrier(id = "b1")
        gateway.spawn(barrier)
        val useCase = DestroyBarrierUseCase(gateway)

        val result = useCase.execute(barrier.id)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        // Despawn was executed: the barrier is no longer tracked and its
        // status entry is gone (the gateway clears the status table on
        // despawn).
        assertThat(gateway.all()).isEmpty()
        assertThat(gateway.statusOf(barrier.id)).isNull()
    }

    @Test
    fun `unknown id returns Failure with NotFound`() {
        val gateway = InMemoryBarrierGateway()
        val useCase = DestroyBarrierUseCase(gateway)
        val unknown = BarrierId("ghost")

        val result = useCase.execute(unknown)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(DestroyBarrierError.NotFound::class.java)
        assertThat((error as DestroyBarrierError.NotFound).id).isEqualTo(unknown)
    }

    @Test
    fun `destroying one barrier leaves siblings intact`() {
        val gateway = InMemoryBarrierGateway()
        val target = barrier(id = "b1")
        val sibling = barrier(id = "b2")
        gateway.spawn(target)
        gateway.spawn(sibling)
        val useCase = DestroyBarrierUseCase(gateway)

        val result = useCase.execute(target.id)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(gateway.all()).containsExactly(sibling)
    }

    @Test
    fun `destroying twice yields NotFound on the second call`() {
        val gateway = InMemoryBarrierGateway()
        val barrier = barrier(id = "b1")
        gateway.spawn(barrier)
        val useCase = DestroyBarrierUseCase(gateway)

        val first = useCase.execute(barrier.id)
        val second = useCase.execute(barrier.id)

        assertThat(first).isInstanceOf(Result.Success::class.java)
        assertThat(second).isInstanceOf(Result.Failure::class.java)
        assertThat(((second as Result.Failure).error as DestroyBarrierError.NotFound).id)
            .isEqualTo(barrier.id)
    }

    @Test
    fun `error messageKey follows the astera_match_barrier_error namespace`() {
        val gateway = InMemoryBarrierGateway()
        val useCase = DestroyBarrierUseCase(gateway)

        val result = useCase.execute(BarrierId("ghost"))

        val error = (result as Result.Failure).error
        assertThat(error.messageKey.value).isEqualTo("astera.match.barrier.error.not-found")
    }

    private fun barrier(id: String): Barrier = Barrier(
        id = BarrierId(id),
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
}
