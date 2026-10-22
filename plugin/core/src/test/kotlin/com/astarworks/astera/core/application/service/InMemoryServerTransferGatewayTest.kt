package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerTransferRequested
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.TransferError
import com.astarworks.astera.core.domain.proxy.TransferReason
import com.astarworks.astera.core.domain.proxy.TransferWhen
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class InMemoryServerTransferGatewayTest {

    private val lobby = ServerName("lobby")
    private val game1 = ServerName("game-1")

    private fun playerId(): PlayerId = PlayerId(UUID.randomUUID())

    private fun fixture(
        neighbors: Set<ServerName> = setOf(game1),
        permissions: InMemoryPermissionService? = null,
    ): Triple<InMemoryServerTransferGateway, InMemoryProxyMessenger, MutableList<DomainEvent>> {
        val messenger = InMemoryProxyMessenger(localServer = lobby, neighbors = neighbors)
        val events = CopyOnWriteArrayList<DomainEvent>()
        val bus = InMemoryDomainEventBus()
        bus.subscribe(DomainEvent::class) { e -> events.add(e) }
        val gateway = InMemoryServerTransferGateway(
            messenger = messenger,
            eventBus = bus,
            permissionService = permissions,
        )
        // Re-subscribe under the bus the gateway also uses (the gateway already
        // installed PlayerJoined / MatchEnded handlers in its init block).
        return Triple(gateway, messenger, events)
    }

    private fun fixtureWithBus(
        bus: InMemoryDomainEventBus,
        permissions: InMemoryPermissionService? = null,
    ): Pair<InMemoryServerTransferGateway, InMemoryProxyMessenger> {
        val messenger = InMemoryProxyMessenger(localServer = lobby, neighbors = setOf(game1))
        val gateway = InMemoryServerTransferGateway(
            messenger = messenger,
            eventBus = bus,
            permissionService = permissions,
        )
        return gateway to messenger
    }

    @Test
    fun `transfer with no permission service succeeds and publishes event`() {
        val (gateway, messenger, events) = fixture()
        val player = playerId()

        val outcome = gateway.transfer(player, game1, TransferReason.MatchFound)

        assertThat(outcome.isSuccess).isTrue()
        assertThat(messenger.transfersRecorded()).containsExactly(
            InMemoryProxyMessenger.TransferRequestRecord(player, game1),
        )
        val transferEvents = events.filterIsInstance<PlayerTransferRequested>()
        assertThat(transferEvents).hasSize(1)
        assertThat(transferEvents[0].playerId).isEqualTo(player)
        assertThat(transferEvents[0].targetServer).isEqualTo(game1)
        assertThat(transferEvents[0].transferReason).isEqualTo(TransferReason.MatchFound)
    }

    @Test
    fun `transfer without role returns PermissionDenied and skips messenger`() {
        val perms = InMemoryPermissionService()
        val (gateway, messenger, _) = fixture(permissions = perms)
        val player = playerId()

        val outcome = gateway.transfer(player, game1, TransferReason.AdminMove)

        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        val err = (outcome as Result.Failure).error
        assertThat(err).isInstanceOf(TransferError.PermissionDenied::class.java)
        assertThat((err as TransferError.PermissionDenied).permissionNode)
            .isEqualTo("astera.transfer.game-1")
        assertThat(messenger.transfersRecorded()).isEmpty()
    }

    @Test
    fun `system-initiated hops skip the permission check`() {
        val (gateway, messenger, _) = fixture(permissions = InMemoryPermissionService())
        val player = playerId()

        assertThat(gateway.transfer(player, game1, TransferReason.MatchFound)).isInstanceOf(Result.Success::class.java)
        assertThat(gateway.transfer(player, game1, TransferReason.LobbyReturn)).isInstanceOf(Result.Success::class.java)
        assertThat(messenger.transfersRecorded()).hasSize(2)
    }

    @Test
    fun `transfer with admin role succeeds`() {
        val perms = InMemoryPermissionService()
        // Default `astera:admin` role grants `*` → passes any node check.
        val player = playerId()
        perms.assign(player, com.astarworks.astera.core.domain.permission.DefaultRoles.ADMIN.id)

        val (gateway, messenger, _) = fixture(permissions = perms)
        val outcome = gateway.transfer(player, game1, TransferReason.AdminMove)

        assertThat(outcome.isSuccess).isTrue()
        assertThat(messenger.transfersRecorded()).hasSize(1)
    }

    @Test
    fun `transfer to unknown target wraps ProxyError as TargetServerOffline`() {
        val (gateway, _, _) = fixture(neighbors = emptySet())
        val outcome = gateway.transfer(playerId(), game1, TransferReason.MatchFound)

        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        val err = (outcome as Result.Failure).error
        assertThat(err).isInstanceOf(TransferError.TargetServerOffline::class.java)
    }

    @Test
    fun `queueTransfer OnNextLogin fires when PlayerJoinedServer is published`() {
        val bus = InMemoryDomainEventBus()
        val (gateway, messenger) = fixtureWithBus(bus)
        val player = playerId()

        gateway.queueTransfer(
            playerId = player,
            targetServer = game1,
            when_ = TransferWhen.OnNextLogin(),
            transferReason = TransferReason.MatchFound,
        )
        assertThat(gateway.queueDepth()).isEqualTo(1)
        assertThat(gateway.pendingFor(player)).isNotNull
        assertThat(messenger.transfersRecorded()).isEmpty()

        bus.publish(PlayerJoinedServer(playerId = player))

        assertThat(gateway.queueDepth()).isZero()
        assertThat(messenger.transfersRecorded()).hasSize(1)
    }

    @Test
    fun `queueTransfer AfterCurrentMatch fires when MatchEnded is published`() {
        val bus = InMemoryDomainEventBus()
        val (gateway, messenger) = fixtureWithBus(bus)
        val player = playerId()
        val match = MatchId("m-99")

        gateway.queueTransfer(
            playerId = player,
            targetServer = game1,
            when_ = TransferWhen.AfterCurrentMatch(match),
            transferReason = TransferReason.LobbyReturn,
        )
        assertThat(gateway.queueDepth()).isEqualTo(1)

        bus.publish(
            MatchEnded(
                matchId = match,
                winningTeam = null,
                atTick = 0L,
                reason = EndReason.TimerExpired,
            ),
        )

        assertThat(gateway.queueDepth()).isZero()
        assertThat(messenger.transfersRecorded()).hasSize(1)
    }

    @Test
    fun `queueTransfer Immediately is held until flushAllImmediate is called`() {
        val (gateway, messenger, _) = fixture()
        val player = playerId()

        gateway.queueTransfer(
            playerId = player,
            targetServer = game1,
            when_ = TransferWhen.Immediately,
            transferReason = TransferReason.MatchFound,
        )
        assertThat(messenger.transfersRecorded()).isEmpty()
        assertThat(gateway.queueDepth()).isEqualTo(1)

        val results = gateway.flushAllImmediate()
        assertThat(results).hasSize(1)
        assertThat(results[0].isSuccess).isTrue()
        assertThat(messenger.transfersRecorded()).hasSize(1)
        assertThat(gateway.queueDepth()).isZero()
    }

    @Test
    fun `queueTransfer past capacity returns QueueFull`() {
        val messenger = InMemoryProxyMessenger(localServer = lobby, neighbors = setOf(game1))
        val bus = InMemoryDomainEventBus()
        val gateway = InMemoryServerTransferGateway(messenger, bus, queueCapacity = 2)

        assertThat(
            gateway.queueTransfer(playerId(), game1, TransferWhen.OnNextLogin(), TransferReason.MatchFound).isSuccess,
        ).isTrue()
        assertThat(
            gateway.queueTransfer(playerId(), game1, TransferWhen.OnNextLogin(), TransferReason.MatchFound).isSuccess,
        ).isTrue()

        val overflow = gateway.queueTransfer(
            playerId(), game1, TransferWhen.OnNextLogin(), TransferReason.MatchFound,
        )
        assertThat(overflow).isInstanceOf(Result.Failure::class.java)
        assertThat((overflow as Result.Failure).error).isEqualTo(TransferError.QueueFull)
    }

    @Test
    fun `permissionNodeFor includes server name`() {
        assertThat(InMemoryServerTransferGateway.permissionNodeFor(ServerName("arena-7")))
            .isEqualTo("astera.transfer.arena-7")
    }

    @Test
    fun `CustomReason carries the supplied key`() {
        val key = com.astarworks.astera.core.domain.i18n.MessageKey("astera.transfer.reason.party_follow")
        val reason = TransferReason.CustomReason(key)
        assertThat(reason.messageKey).isEqualTo(key)
    }

    @Test
    fun `TransferWhen OnNextLogin rejects negative timeout`() {
        org.assertj.core.api.Assertions.assertThatThrownBy {
            TransferWhen.OnNextLogin(timeoutSeconds = -1)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
