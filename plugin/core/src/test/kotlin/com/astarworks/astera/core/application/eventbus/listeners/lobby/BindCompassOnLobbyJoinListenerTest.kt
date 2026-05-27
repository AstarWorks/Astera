package com.astarworks.astera.core.application.eventbus.listeners.lobby

import com.astarworks.astera.core.application.service.inventory.InMemoryInventoryMutator
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerReturnedToLobby
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5lobby-content (ADR-0119) — verifies the compass-bind listener
 * places a menu-compass into hotbar slot 0 on both lobby-entry signals.
 */
class BindCompassOnLobbyJoinListenerTest {

    private val playerId = PlayerId(UUID.randomUUID())

    @Test
    fun `handleJoin places menu-compass into hotbar slot 0`() {
        val mutator = InMemoryInventoryMutator()
        val listener = BindCompassOnLobbyJoinListener(mutator)

        listener.handleJoin(PlayerJoinedServer(playerId = playerId))

        assertThat(mutator.count(playerId, ItemId("menu-compass"))).isEqualTo(1)
    }

    @Test
    fun `handleReturn places menu-compass into hotbar slot 0`() {
        val mutator = InMemoryInventoryMutator()
        val listener = BindCompassOnLobbyJoinListener(mutator)

        listener.handleReturn(PlayerReturnedToLobby(playerId = playerId, atTick = 100L))

        assertThat(mutator.count(playerId, ItemId("menu-compass"))).isEqualTo(1)
    }

    @Test
    fun `re-bind on subsequent joins is idempotent at slot 0`() {
        val mutator = InMemoryInventoryMutator()
        val listener = BindCompassOnLobbyJoinListener(mutator)

        listener.handleJoin(PlayerJoinedServer(playerId = playerId))
        listener.handleReturn(PlayerReturnedToLobby(playerId = playerId, atTick = 200L))
        listener.handleJoin(PlayerJoinedServer(playerId = playerId))

        // setSlot overwrites — exactly one compass at slot 0 regardless of
        // how many times the listener fires.
        assertThat(mutator.count(playerId, ItemId("menu-compass"))).isEqualTo(1)
    }
}
