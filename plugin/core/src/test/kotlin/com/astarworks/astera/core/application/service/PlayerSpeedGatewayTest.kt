package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.SpeedMutationError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerFlightToggled
import com.astarworks.astera.core.domain.event.PlayerNoClipChanged
import com.astarworks.astera.core.domain.event.PlayerSpeedChanged
import com.astarworks.astera.core.domain.event.PlayerSprintingChanged
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.SpeedAxis
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5k (ADR-0080) — [InMemoryPlayerSpeedGateway] contract.
 */
class PlayerSpeedGatewayTest {

    private lateinit var scheduler: FakeScheduler
    private lateinit var eventBus: InMemoryDomainEventBus
    private lateinit var gateway: InMemoryPlayerSpeedGateway
    private val pid: PlayerId = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        scheduler = FakeScheduler()
        eventBus = InMemoryDomainEventBus()
        gateway = InMemoryPlayerSpeedGateway(scheduler, eventBus)
    }

    @Test
    fun `setMultiplier stores value and publishes event`() {
        val captured = mutableListOf<PlayerSpeedChanged>()
        eventBus.subscribe(PlayerSpeedChanged::class) { captured += it }

        gateway.setMultiplier(pid, SpeedAxis.WALK, 1.5, 40)

        assertThat(gateway.currentMultiplier(pid, SpeedAxis.WALK)).isEqualTo(1.5)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].axis).isEqualTo(SpeedAxis.WALK)
        assertThat(captured[0].newMultiplier).isEqualTo(1.5)
        assertThat(captured[0].ttlTicks).isEqualTo(40)
    }

    @Test
    fun `setMultiplier auto-resets after TTL`() {
        gateway.setMultiplier(pid, SpeedAxis.WALK, 2.0, 10)
        scheduler.advanceTicks(11L)

        assertThat(gateway.currentMultiplier(pid, SpeedAxis.WALK)).isNull()
    }

    @Test
    fun `setMultiplier MAX_VALUE TTL does not expire`() {
        gateway.setMultiplier(pid, SpeedAxis.WALK, 2.0, Int.MAX_VALUE)
        scheduler.advanceTicks(10_000L)

        assertThat(gateway.currentMultiplier(pid, SpeedAxis.WALK)).isEqualTo(2.0)
    }

    @Test
    fun `walk and fly axes are independent`() {
        gateway.setMultiplier(pid, SpeedAxis.WALK, 1.5, Int.MAX_VALUE)
        gateway.setMultiplier(pid, SpeedAxis.FLY, 3.0, Int.MAX_VALUE)

        assertThat(gateway.currentMultiplier(pid, SpeedAxis.WALK)).isEqualTo(1.5)
        assertThat(gateway.currentMultiplier(pid, SpeedAxis.FLY)).isEqualTo(3.0)
    }

    @Test
    fun `setMultiplier rejects invalid values`() {
        assertThat(gateway.setMultiplier(pid, SpeedAxis.WALK, -1.0, 10))
            .isInstanceOf(Result.Failure::class.java)
        assertThat(gateway.setMultiplier(pid, SpeedAxis.WALK, Double.NaN, 10))
            .isInstanceOf(Result.Failure::class.java)
        assertThat(gateway.setMultiplier(pid, SpeedAxis.WALK, 1.0, 0))
            .isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `resetMultiplier clears value and emits event`() {
        val captured = mutableListOf<PlayerSpeedChanged>()
        gateway.setMultiplier(pid, SpeedAxis.WALK, 2.0, Int.MAX_VALUE)
        eventBus.subscribe(PlayerSpeedChanged::class) { captured += it }

        gateway.resetMultiplier(pid, SpeedAxis.WALK)

        assertThat(gateway.currentMultiplier(pid, SpeedAxis.WALK)).isNull()
        assertThat(captured).hasSize(1)
        assertThat(captured[0].newMultiplier).isEqualTo(1.0)
    }

    @Test
    fun `setAllowFlight stores and publishes event`() {
        val captured = mutableListOf<PlayerFlightToggled>()
        eventBus.subscribe(PlayerFlightToggled::class) { captured += it }

        gateway.setAllowFlight(pid, true, 100)

        assertThat(gateway.isFlightAllowed(pid)).isTrue()
        assertThat(captured.last().allowed).isTrue()
    }

    @Test
    fun `setAllowFlight auto-resets after TTL`() {
        gateway.setAllowFlight(pid, true, 10)
        scheduler.advanceTicks(11L)
        assertThat(gateway.isFlightAllowed(pid)).isFalse()
    }

    @Test
    fun `setSprinting stores and publishes event`() {
        val captured = mutableListOf<PlayerSprintingChanged>()
        eventBus.subscribe(PlayerSprintingChanged::class) { captured += it }

        gateway.setSprinting(pid, true, 100)

        assertThat(gateway.isSprintingForced(pid)).isTrue()
        assertThat(captured.last().sprinting).isTrue()
    }

    @Test
    fun `setSprinting auto-resets after TTL`() {
        gateway.setSprinting(pid, true, 10)
        scheduler.advanceTicks(11L)
        assertThat(gateway.isSprintingForced(pid)).isFalse()
    }

    @Test
    fun `setNoClip stores and publishes event`() {
        val captured = mutableListOf<PlayerNoClipChanged>()
        eventBus.subscribe(PlayerNoClipChanged::class) { captured += it }

        gateway.setNoClip(pid, true, 100)

        assertThat(gateway.isNoClipActive(pid)).isTrue()
        assertThat(captured.last().noClip).isTrue()
    }

    @Test
    fun `replacing multiplier cancels prior expiry task`() {
        gateway.setMultiplier(pid, SpeedAxis.WALK, 2.0, 10)
        gateway.setMultiplier(pid, SpeedAxis.WALK, 3.0, 100)

        scheduler.advanceTicks(15L)

        // First task should be cancelled — value stays at 3.0
        assertThat(gateway.currentMultiplier(pid, SpeedAxis.WALK)).isEqualTo(3.0)
    }

    @Test
    fun `error variants compare correctly`() {
        val err = SpeedMutationError.InvalidValue("multiplier", -1.0)
        val outcome = gateway.setMultiplier(pid, SpeedAxis.WALK, -1.0, 10)
        assertThat(outcome).isEqualTo(Result.failure(err))
    }
}
