package com.astarworks.astera.match.application.usecase.barrier

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.barrier.BarrierId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.service.InMemoryBarrierGateway
import com.astarworks.astera.match.domain.barrier.BarrierStatus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CreateBarrierUseCaseTest {

    private val creator = PlayerId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val origin = Vec3(2.0, 64.0, 2.0)
    private val validRegion = Region(
        min = Vec3(0.0, 64.0, 0.0),
        max = Vec3(4.0, 68.0, 4.0),
    )
    private val testWorldId = WorldId("00000000-0000-0000-0000-0000000000aa")

    @Test
    fun `happy path spawns the barrier and stamps createdAtTick from the scheduler`() {
        val gateway = InMemoryBarrierGateway()
        val scheduler = FakeScheduler(startTick = 42L)
        val useCase = CreateBarrierUseCase(gateway, scheduler)

        val result = useCase.execute(
            id = BarrierId("b1"),
            worldId = testWorldId,
            region = validRegion,
            origin = origin,
            creator = creator,
            slowdownPercent = 0.25,
            detectionEnabled = true,
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val barrier = (result as Result.Success).value
        assertThat(barrier.id).isEqualTo(BarrierId("b1"))
        assertThat(barrier.createdAtTick).isEqualTo(42L)
        assertThat(barrier.region).isEqualTo(validRegion)
        assertThat(barrier.origin).isEqualTo(origin)
        assertThat(barrier.creator).isEqualTo(creator)
        assertThat(barrier.slowdownPercent).isEqualTo(0.25)
        assertThat(barrier.detectionEnabled).isTrue()

        // Gateway-side: the barrier is registered and defaulted to Active.
        assertThat(gateway.all()).containsExactly(barrier)
        assertThat(gateway.statusOf(barrier.id)).isEqualTo(BarrierStatus.Active)
    }

    @Test
    fun `default slowdownPercent is the documented 25 percent`() {
        val gateway = InMemoryBarrierGateway()
        val scheduler = FakeScheduler()
        val useCase = CreateBarrierUseCase(gateway, scheduler)

        val result = useCase.execute(
            id = BarrierId("b1"),
            worldId = testWorldId,
            region = validRegion,
            origin = origin,
            creator = creator,
        )

        val barrier = (result as Result.Success).value
        assertThat(barrier.slowdownPercent).isEqualTo(CreateBarrierUseCase.DEFAULT_SLOWDOWN_PERCENT)
        assertThat(barrier.slowdownPercent).isEqualTo(0.25)
    }

    @Test
    fun `empty region returns Failure with EmptyRegion error`() {
        val gateway = InMemoryBarrierGateway()
        val scheduler = FakeScheduler()
        val useCase = CreateBarrierUseCase(gateway, scheduler)
        // Zero-volume region: single point — `Region.init` allows min == max,
        // but `volume` is 0, which our use case rejects.
        val pointRegion = Region(min = origin, max = origin)

        val result = useCase.execute(
            id = BarrierId("b1"),
            worldId = testWorldId,
            region = pointRegion,
            origin = origin,
            creator = creator,
        )

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(CreateBarrierError.EmptyRegion::class.java)
        assertThat((error as CreateBarrierError.EmptyRegion).region).isEqualTo(pointRegion)
        // Gateway untouched on failure.
        assertThat(gateway.all()).isEmpty()
    }

    @Test
    fun `slowdownPercent above 1_0 returns Failure with InvalidSlowdown error`() {
        val gateway = InMemoryBarrierGateway()
        val scheduler = FakeScheduler()
        val useCase = CreateBarrierUseCase(gateway, scheduler)

        val result = useCase.execute(
            id = BarrierId("b1"),
            worldId = testWorldId,
            region = validRegion,
            origin = origin,
            creator = creator,
            slowdownPercent = 1.5,
        )

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(CreateBarrierError.InvalidSlowdown::class.java)
        assertThat((error as CreateBarrierError.InvalidSlowdown).value).isEqualTo(1.5)
        assertThat(gateway.all()).isEmpty()
    }

    @Test
    fun `negative slowdownPercent returns Failure with InvalidSlowdown error`() {
        val gateway = InMemoryBarrierGateway()
        val scheduler = FakeScheduler()
        val useCase = CreateBarrierUseCase(gateway, scheduler)

        val result = useCase.execute(
            id = BarrierId("b1"),
            worldId = testWorldId,
            region = validRegion,
            origin = origin,
            creator = creator,
            slowdownPercent = -0.1,
        )

        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(CreateBarrierError.InvalidSlowdown::class.java)
        assertThat((error as CreateBarrierError.InvalidSlowdown).value).isEqualTo(-0.1)
    }

    @Test
    fun `error messageKey follows the astera_match_barrier_error namespace`() {
        val gateway = InMemoryBarrierGateway()
        val scheduler = FakeScheduler()
        val useCase = CreateBarrierUseCase(gateway, scheduler)

        val emptyResult = useCase.execute(
            id = BarrierId("b1"),
            worldId = testWorldId,
            region = Region(min = origin, max = origin),
            origin = origin,
            creator = creator,
        )
        val invalidResult = useCase.execute(
            id = BarrierId("b2"),
            worldId = testWorldId,
            region = validRegion,
            origin = origin,
            creator = creator,
            slowdownPercent = 2.0,
        )

        assertThat((emptyResult as Result.Failure).error.messageKey.value)
            .isEqualTo("astera.match.barrier.error.empty-region")
        assertThat((invalidResult as Result.Failure).error.messageKey.value)
            .isEqualTo("astera.match.barrier.error.invalid-slowdown")
    }
}
