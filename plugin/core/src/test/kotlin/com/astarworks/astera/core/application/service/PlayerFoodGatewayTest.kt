package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerFoodChanged
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5k (ADR-0080) — [InMemoryPlayerFoodGateway] contract.
 */
class PlayerFoodGatewayTest {

    private lateinit var scheduler: FakeScheduler
    private lateinit var eventBus: InMemoryDomainEventBus
    private lateinit var gateway: InMemoryPlayerFoodGateway
    private val pid: PlayerId = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        scheduler = FakeScheduler()
        eventBus = InMemoryDomainEventBus()
        gateway = InMemoryPlayerFoodGateway(scheduler, eventBus)
    }

    @Test
    fun `mirrorEnergy writes the level and zeroes saturation`() {
        val captured = mutableListOf<PlayerFoodChanged>()
        eventBus.subscribe(PlayerFoodChanged::class) { captured += it }

        gateway.mirrorEnergy(pid, 10)

        assertThat(gateway.getFoodLevel(pid)).isEqualTo(10)
        assertThat(gateway.getSaturation(pid)).isEqualTo(0.0f)
        assertThat(captured.last().foodLevel).isEqualTo(10)
    }

    @Test
    fun `mirrorEnergy rejects out-of-range levels`() {
        assertThat(gateway.mirrorEnergy(pid, -1)).isInstanceOf(Result.Failure::class.java)
        assertThat(gateway.mirrorEnergy(pid, 21)).isInstanceOf(Result.Failure::class.java)
        assertThat(gateway.getFoodLevel(pid)).isNull()
    }

    @Test
    fun `setFoodLevel rejects out-of-range`() {
        assertThat(gateway.setFoodLevel(pid, -1)).isInstanceOf(Result.Failure::class.java)
        assertThat(gateway.setFoodLevel(pid, 21)).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `setSaturation rejects negative or non-finite`() {
        assertThat(gateway.setSaturation(pid, -1.0f)).isInstanceOf(Result.Failure::class.java)
        assertThat(gateway.setSaturation(pid, Float.NaN)).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `setSaturation clamps to current food level`() {
        gateway.setFoodLevel(pid, 5)
        gateway.setSaturation(pid, 999.0f)

        assertThat(gateway.getSaturation(pid)).isEqualTo(5.0f)
    }

    @Test
    fun `getFoodLevel and getSaturation return null before any mutation`() {
        assertThat(gateway.getFoodLevel(pid)).isNull()
        assertThat(gateway.getSaturation(pid)).isNull()
    }
}
