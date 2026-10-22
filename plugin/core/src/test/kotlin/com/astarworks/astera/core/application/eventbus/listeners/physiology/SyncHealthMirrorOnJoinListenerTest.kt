package com.astarworks.astera.core.application.eventbus.listeners.physiology

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.service.InMemoryPlayerAttributeContainer
import com.astarworks.astera.core.application.service.InMemoryPlayerHealthGateway
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class SyncHealthMirrorOnJoinListenerTest {

    private val pid = PlayerId(UUID.randomUUID())
    private val inMemory = InMemoryPlayerHealthGateway(InMemoryPlayerAttributeContainer(), FakeScheduler(), InMemoryDomainEventBus())

    /** Records every write so the test sees what the Paper gateway would mirror to Bukkit. */
    private class Recording(private val delegate: IPlayerHealthGateway) : IPlayerHealthGateway by delegate {
        val healthWrites = mutableListOf<Double>()
        override fun setHealth(playerId: PlayerId, hp: Double, source: HealthChangeSource) =
            delegate.setHealth(playerId, hp, source).also { healthWrites += hp }
    }

    @Test
    fun `a container with no recorded HP is made full so the mirror is written`() {
        val gw = Recording(inMemory)
        SyncHealthMirrorOnJoinListener({ gw }).handle(PlayerJoinedServer(pid))

        assertThat(gw.getMaxHealth(pid)).isEqualTo(20.0)
        assertThat(gw.getCurrentHealth(pid)).isEqualTo(20.0)
        assertThat(gw.healthWrites).containsExactly(20.0)
    }

    @Test
    fun `a recorded HP is re-mirrored unchanged`() {
        inMemory.setMaxHealth(pid, 40.0)
        inMemory.setHealth(pid, 13.0, HealthChangeSource.AdminCommand)
        val gw = Recording(inMemory)

        SyncHealthMirrorOnJoinListener({ gw }).handle(PlayerJoinedServer(pid))

        assertThat(gw.getCurrentHealth(pid)).isEqualTo(13.0)
        assertThat(gw.getMaxHealth(pid)).isEqualTo(40.0)
        assertThat(gw.healthWrites).containsExactly(13.0)
    }

    @Test
    fun `no health gateway is a no-op`() {
        SyncHealthMirrorOnJoinListener({ null }).handle(PlayerJoinedServer(pid))
    }
}
