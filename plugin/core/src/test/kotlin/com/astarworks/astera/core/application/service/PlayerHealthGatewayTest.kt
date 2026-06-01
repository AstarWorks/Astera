package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.HealthMutationError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerHealthChanged
import com.astarworks.astera.core.domain.event.PlayerMaxHealthChanged
import com.astarworks.astera.core.domain.event.PlayerRegenRateChanged
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5k (ADR-0080) — [InMemoryPlayerHealthGateway] contract.
 */
class PlayerHealthGatewayTest {

    private lateinit var scheduler: FakeScheduler
    private lateinit var eventBus: InMemoryDomainEventBus
    private lateinit var containerStore: InMemoryPlayerAttributeContainer
    private lateinit var gateway: InMemoryPlayerHealthGateway

    private val pid: PlayerId = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        scheduler = FakeScheduler()
        eventBus = InMemoryDomainEventBus()
        // Wave 6a-redesign Step 2 (ADR-0150): gateway routes HP through
        // IAttributeContainer; test injects the same InMemory store used
        // in production wiring.
        containerStore = InMemoryPlayerAttributeContainer()
        gateway = InMemoryPlayerHealthGateway(containerStore, scheduler, eventBus)
    }

    @Test
    fun `setMaxHealth stores value and publishes PlayerMaxHealthChanged`() {
        val captured = mutableListOf<PlayerMaxHealthChanged>()
        eventBus.subscribe(PlayerMaxHealthChanged::class) { captured += it }

        val outcome = gateway.setMaxHealth(pid, 40.0)

        assertThat(outcome).isInstanceOf(Result.Success::class.java)
        assertThat(gateway.getMaxHealth(pid)).isEqualTo(40.0)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].newMaxHp).isEqualTo(40.0)
        assertThat(captured[0].oldMaxHp).isNull()
    }

    @Test
    fun `setMaxHealth rejects non-finite or non-positive values`() {
        assertThat(gateway.setMaxHealth(pid, 0.0)).isInstanceOf(Result.Failure::class.java)
        assertThat(gateway.setMaxHealth(pid, -5.0)).isInstanceOf(Result.Failure::class.java)
        assertThat(gateway.setMaxHealth(pid, Double.NaN)).isInstanceOf(Result.Failure::class.java)
        assertThat(gateway.setMaxHealth(pid, Double.POSITIVE_INFINITY)).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `setMaxHealth re-clamps overflowing current HP`() {
        gateway.setMaxHealth(pid, 40.0)
        gateway.setHealth(pid, 35.0, HealthChangeSource.AdminCommand)

        gateway.setMaxHealth(pid, 20.0)

        assertThat(gateway.getCurrentHealth(pid)).isEqualTo(20.0)
    }

    @Test
    fun `setHealth clamps to max-health and publishes change`() {
        val captured = mutableListOf<PlayerHealthChanged>()
        eventBus.subscribe(PlayerHealthChanged::class) { captured += it }

        gateway.setMaxHealth(pid, 20.0)
        gateway.setHealth(pid, 999.0, HealthChangeSource.AdminCommand)

        assertThat(gateway.getCurrentHealth(pid)).isEqualTo(20.0)
        assertThat(captured.last().newHp).isEqualTo(20.0)
        assertThat(captured.last().source).isEqualTo(HealthChangeSource.AdminCommand)
    }

    @Test
    fun `setHealth rejects negative or non-finite`() {
        assertThat(gateway.setHealth(pid, -1.0, HealthChangeSource.AdminCommand))
            .isEqualTo(Result.failure(HealthMutationError.InvalidValue("hp", -1.0)))
        assertThat(gateway.setHealth(pid, Double.NaN, HealthChangeSource.AdminCommand))
            .isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `heal adds to current HP up to max`() {
        gateway.setMaxHealth(pid, 20.0)
        gateway.setHealth(pid, 5.0, HealthChangeSource.AdminCommand)

        gateway.heal(pid, 100.0, HealthChangeSource.Item)

        assertThat(gateway.getCurrentHealth(pid)).isEqualTo(20.0)
    }

    @Test
    fun `damage subtracts from current HP clamped at zero`() {
        gateway.setMaxHealth(pid, 20.0)
        gateway.setHealth(pid, 15.0, HealthChangeSource.AdminCommand)

        gateway.damage(pid, 100.0, HealthChangeSource.AdminCommand)

        assertThat(gateway.getCurrentHealth(pid)).isEqualTo(0.0)
    }

    @Test
    fun `damage emits event with the admin source variant`() {
        val captured = mutableListOf<PlayerHealthChanged>()
        eventBus.subscribe(PlayerHealthChanged::class) { captured += it }

        gateway.setMaxHealth(pid, 20.0)
        gateway.setHealth(pid, 20.0, HealthChangeSource.AdminCommand)
        captured.clear()

        gateway.damage(pid, 5.0, HealthChangeSource.AdminCommand)

        assertThat(captured).hasSize(1)
        assertThat(captured[0].source).isEqualTo(HealthChangeSource.AdminCommand)
        assertThat(captured[0].oldHp).isEqualTo(20.0)
        assertThat(captured[0].newHp).isEqualTo(15.0)
    }

    @Test
    fun `setRegenRate emits the rate-change event and starts ticking`() {
        val regenEvents = mutableListOf<PlayerRegenRateChanged>()
        val healthEvents = mutableListOf<PlayerHealthChanged>()
        eventBus.subscribe(PlayerRegenRateChanged::class) { regenEvents += it }
        eventBus.subscribe(PlayerHealthChanged::class) { healthEvents += it }

        gateway.setMaxHealth(pid, 20.0)
        gateway.setHealth(pid, 10.0, HealthChangeSource.AdminCommand)
        healthEvents.clear()

        val outcome = gateway.setRegenRate(pid, hpPerSecond = 1.0, ttlTicks = 40)

        assertThat(outcome).isInstanceOf(Result.Success::class.java)
        assertThat(regenEvents).hasSize(1)
        assertThat(regenEvents[0].hpPerSecond).isEqualTo(1.0)
        assertThat(regenEvents[0].ttlTicks).isEqualTo(40)
        assertThat(gateway.hasActiveRegen(pid)).isTrue()
    }

    @Test
    fun `regen ticks add HP per second over time`() {
        gateway.setMaxHealth(pid, 20.0)
        gateway.setHealth(pid, 10.0, HealthChangeSource.AdminCommand)
        gateway.setRegenRate(pid, hpPerSecond = 4.0, ttlTicks = 100)

        // 20 ticks @ 4 HP/s = 0.2 HP/tick × 20 = 4.0 HP
        scheduler.advanceTicks(20)

        assertThat(gateway.getCurrentHealth(pid)!!).isCloseTo(14.0, within(0.001))
    }

    @Test
    fun `regen TTL expiry auto-clears`() {
        gateway.setMaxHealth(pid, 20.0)
        gateway.setHealth(pid, 10.0, HealthChangeSource.AdminCommand)
        gateway.setRegenRate(pid, hpPerSecond = 1.0, ttlTicks = 5)

        scheduler.advanceTicks(10)

        assertThat(gateway.hasActiveRegen(pid)).isFalse()
    }

    @Test
    fun `setRegenRate zero acts as clearRegen`() {
        gateway.setMaxHealth(pid, 20.0)
        gateway.setRegenRate(pid, hpPerSecond = 1.0, ttlTicks = 100)
        assertThat(gateway.hasActiveRegen(pid)).isTrue()

        gateway.setRegenRate(pid, hpPerSecond = 0.0, ttlTicks = 100)

        assertThat(gateway.hasActiveRegen(pid)).isFalse()
    }

    @Test
    fun `clearRegen is idempotent`() {
        gateway.clearRegen(pid)
        gateway.clearRegen(pid)
        // No exception, no active regen.
        assertThat(gateway.hasActiveRegen(pid)).isFalse()
    }

    @Test
    fun `setRegenRate with MAX_VALUE TTL never expires`() {
        gateway.setMaxHealth(pid, 20.0)
        gateway.setRegenRate(pid, hpPerSecond = 1.0, ttlTicks = Int.MAX_VALUE)

        scheduler.advanceTicks(10_000L)

        assertThat(gateway.hasActiveRegen(pid)).isTrue()
    }

    @Test
    fun `setRegenRate rejects invalid arguments`() {
        assertThat(gateway.setRegenRate(pid, -1.0, 100)).isInstanceOf(Result.Failure::class.java)
        assertThat(gateway.setRegenRate(pid, Double.NaN, 100)).isInstanceOf(Result.Failure::class.java)
        assertThat(gateway.setRegenRate(pid, 1.0, 0)).isInstanceOf(Result.Failure::class.java)
        assertThat(gateway.setRegenRate(pid, 1.0, -5)).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `replacing regen rate cancels the prior slot`() {
        gateway.setMaxHealth(pid, 20.0)
        gateway.setRegenRate(pid, hpPerSecond = 1.0, ttlTicks = 100)
        gateway.setRegenRate(pid, hpPerSecond = 2.0, ttlTicks = 200)

        assertThat(gateway.currentRegenRate(pid)).isEqualTo(2.0)
    }

    @Test
    fun `getCurrentHealth returns null before any mutation`() {
        assertThat(gateway.getCurrentHealth(pid)).isNull()
    }

    @Test
    fun `getMaxHealth returns null before any mutation`() {
        assertThat(gateway.getMaxHealth(pid)).isNull()
    }
}
