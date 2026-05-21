package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.event.PlayerProtectionExpired
import com.astarworks.astera.core.domain.event.PlayerProtectionGranted
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemorySpawnProtectionGatewayTest {

    private val playerId = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000abc"))

    private lateinit var scheduler: FakeScheduler
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var gateway: InMemorySpawnProtectionGateway

    @BeforeEach
    fun setUp() {
        scheduler = FakeScheduler(startTick = 0L)
        eventBus = FakeDomainEventBus()
        gateway = InMemorySpawnProtectionGateway(scheduler, eventBus)
    }

    @Test
    fun `grantProtection sets isProtected true and publishes Granted`() {
        gateway.grantProtection(playerId, 5)
        assertThat(gateway.isProtected(playerId)).isTrue
        val granted = eventBus.publishedOfType<PlayerProtectionGranted>().single()
        assertThat(granted.playerId).isEqualTo(playerId)
        assertThat(granted.durationSeconds).isEqualTo(5)
    }

    @Test
    fun `protection expires after duration ticks and fires Expired`() {
        gateway.grantProtection(playerId, 1)
        assertThat(gateway.isProtected(playerId)).isTrue
        scheduler.advanceTicks(20) // 1s = 20 ticks
        assertThat(gateway.isProtected(playerId)).isFalse
        assertThat(eventBus.publishedOfType<PlayerProtectionExpired>()).hasSize(1)
    }

    @Test
    fun `revokeProtection drops the window and fires Expired`() {
        gateway.grantProtection(playerId, 10)
        gateway.revokeProtection(playerId)
        assertThat(gateway.isProtected(playerId)).isFalse
        assertThat(eventBus.publishedOfType<PlayerProtectionExpired>()).hasSize(1)
    }

    @Test
    fun `grantProtection with zero or negative duration is a no-op`() {
        gateway.grantProtection(playerId, 0)
        gateway.grantProtection(playerId, -5)
        assertThat(gateway.isProtected(playerId)).isFalse
        assertThat(eventBus.publishedOfType<PlayerProtectionGranted>()).isEmpty()
    }

    @Test
    fun `granting a second time extends the protection window`() {
        gateway.grantProtection(playerId, 5)  // expires at tick 100
        scheduler.advanceTicks(50)            // now at tick 50, still protected
        gateway.grantProtection(playerId, 5)  // new expiry = max(100, 50+100) = 150
        scheduler.advanceTicks(60)            // now at tick 110 — would have expired under the first grant
        assertThat(gateway.isProtected(playerId)).isTrue
        scheduler.advanceTicks(50)            // now at tick 160
        assertThat(gateway.isProtected(playerId)).isFalse
    }

    @Test
    fun `revokeProtection on un-protected player is idempotent`() {
        gateway.revokeProtection(playerId)
        assertThat(eventBus.publishedOfType<PlayerProtectionExpired>()).isEmpty()
    }
}
