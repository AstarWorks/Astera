package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.FoodMutationError
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
    fun `suppressHungerHud sets food to 20 and locks saturation`() {
        val captured = mutableListOf<PlayerFoodChanged>()
        eventBus.subscribe(PlayerFoodChanged::class) { captured += it }

        gateway.suppressHungerHud(pid, suppress = true)

        assertThat(gateway.isHungerSuppressed(pid)).isTrue()
        assertThat(gateway.getFoodLevel(pid)).isEqualTo(20)
        assertThat(gateway.getSaturation(pid)).isEqualTo(20.0f)
        assertThat(captured.last().suppressed).isTrue()
    }

    @Test
    fun `suppressHungerHud false unsets the flag without resetting saturation`() {
        gateway.suppressHungerHud(pid, suppress = true)
        gateway.suppressHungerHud(pid, suppress = false)

        assertThat(gateway.isHungerSuppressed(pid)).isFalse()
    }

    @Test
    fun `setHungerAsMana maps 0_5 to 10`() {
        gateway.setHungerAsMana(pid, 0.5f)
        assertThat(gateway.getFoodLevel(pid)).isEqualTo(10)
        assertThat(gateway.getSaturation(pid)).isEqualTo(0.0f)
    }

    @Test
    fun `setHungerAsMana clamps 0 and 1 boundaries`() {
        gateway.setHungerAsMana(pid, 0.0f)
        assertThat(gateway.getFoodLevel(pid)).isEqualTo(0)

        gateway.setHungerAsMana(pid, 1.0f)
        assertThat(gateway.getFoodLevel(pid)).isEqualTo(20)
    }

    @Test
    fun `setHungerAsMana rejects out-of-range values`() {
        assertThat(gateway.setHungerAsMana(pid, -0.1f)).isInstanceOf(Result.Failure::class.java)
        assertThat(gateway.setHungerAsMana(pid, 1.5f)).isInstanceOf(Result.Failure::class.java)
        assertThat(gateway.setHungerAsMana(pid, Float.NaN)).isInstanceOf(Result.Failure::class.java)
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

    @Test
    fun `suppress true overrides prior setHungerAsMana state`() {
        gateway.setHungerAsMana(pid, 0.3f)
        gateway.suppressHungerHud(pid, suppress = true)

        assertThat(gateway.isHungerSuppressed(pid)).isTrue()
        assertThat(gateway.getFoodLevel(pid)).isEqualTo(20)
    }
}
