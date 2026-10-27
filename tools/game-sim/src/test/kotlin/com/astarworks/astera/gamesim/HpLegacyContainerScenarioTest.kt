package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A persisted 20-scale HP container (pre item 18) must not survive a join: max is forced to 100, current is clamped. */
class HpLegacyContainerScenarioTest {

    @Test
    fun `a legacy max 20 container is forced to max 100 on join`() {
        scenario("hp-legacy-container") {
            val player = player("p", team = "red")
            val container = sim.services.getOrThrow<IPlayerAttributeContainerStore>().containerFor(player.id)
            container.set(Attributes.MAX_HP, AttributeValue.NumberValue(20.0))
            container.set(Attributes.CURRENT_HP, AttributeValue.NumberValue(20.0))

            sim.eventBus.publish(PlayerJoinedServer(playerId = player.id))
            advance(1)

            val health = sim.services.getOrThrow<IPlayerHealthGateway>()
            assertThat(health.getMaxHealth(player.id)).isEqualTo(Attributes.DEFAULT_PLAYER_MAX_HP)
        }
    }
}
