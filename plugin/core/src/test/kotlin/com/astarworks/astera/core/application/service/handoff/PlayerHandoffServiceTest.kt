package com.astarworks.astera.core.application.service.handoff

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerInventoryGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.port.outbound.IProxyMessenger
import com.astarworks.astera.core.application.service.InMemoryPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.service.InMemoryPlayerSessionRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerArrivedOnServer
import com.astarworks.astera.core.domain.player.InventoryItemSlot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.core.domain.proxy.HandoffToken
import com.astarworks.astera.core.domain.proxy.ProxyChannel
import com.astarworks.astera.core.domain.proxy.ProxyError
import com.astarworks.astera.core.domain.proxy.ProxyMessage
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class PlayerHandoffServiceTest {

    private val lobby = ServerName("lobby")
    private val game = ServerName("game")
    private val player = PlayerId(UUID.randomUUID())

    /** Records what a server sends and lets the test deliver messages whenever it likes. */
    private class WireMessenger : IProxyMessenger {
        val sent = mutableListOf<Pair<ServerName, ProxyMessage>>()
        private val handlers = mutableListOf<(ProxyMessage) -> Unit>()

        override fun sendToServer(playerId: PlayerId, targetServer: ServerName): Result<Unit, ProxyError> = Result.Success(Unit)

        override fun sendMessage(targetServer: ServerName, channel: ProxyChannel, payload: ByteArray): Result<Unit, ProxyError> {
            sent += targetServer to ProxyMessage(ServerName("src"), channel, payload)
            return Result.Success(Unit)
        }

        override fun broadcast(channel: ProxyChannel, payload: ByteArray): Result<Unit, ProxyError> = Result.Success(Unit)

        override fun subscribe(channel: ProxyChannel, handler: (ProxyMessage) -> Unit): IProxyMessenger.Subscription {
            handlers += handler
            return object : IProxyMessenger.Subscription {
                override fun cancel() { handlers -= handler }
                override val isActive: Boolean get() = handler in handlers
            }
        }

        fun deliver(message: ProxyMessage) = handlers.toList().forEach { it(message) }
    }

    private class FakeInventory : IPlayerInventoryGateway {
        var items: List<InventoryItemSlot?> = List(9) { null }
        var applied = 0
        override fun capture(playerId: PlayerId): PlayerInventorySnapshot =
            PlayerInventorySnapshot.empty(playerId).copy(hotbar = items)

        override fun apply(snapshot: PlayerInventorySnapshot): Boolean {
            items = snapshot.hotbar
            applied++
            return true
        }
    }

    private class Server(
        name: ServerName,
        awaitToken: Boolean,
        repo: IPlayerInventorySnapshotRepository,
        val scheduler: FakeScheduler = FakeScheduler(),
        clock: () -> Long,
    ) {
        val messenger = WireMessenger()
        val inventory = FakeInventory()
        val sessions = InMemoryPlayerSessionRepository()
        val bus = InMemoryDomainEventBus()
        val arrivals = mutableListOf<PlayerArrivedOnServer>()
        var held = false
        val service = PlayerHandoffService(
            localServer = name,
            awaitToken = awaitToken,
            messenger = messenger,
            snapshots = repo,
            inventory = { inventory },
            sessions = sessions,
            scheduler = scheduler,
            eventBus = bus,
            isHeld = { held },
            clockMillis = clock,
        ).also { it.start() }

        init {
            bus.subscribe(PlayerArrivedOnServer::class) { arrivals += it }
        }
    }

    private var now = 1_000L
    private val sword = listOf<InventoryItemSlot?>(InventoryItemSlot.ContentItem("sword", 1)) + List(8) { null }

    private fun tokenFrom(source: Server, target: ServerName): ProxyMessage =
        source.messenger.sent.last { it.first == target }.second

    @Test
    fun `source saves before the token and the quit does not save again`() {
        val repo = InMemoryPlayerInventorySnapshotRepository()
        val source = Server(lobby, awaitToken = true, repo) { now }
        source.inventory.items = sword

        val version = source.service.onTransferRequested(player, game)

        assertThat(version).isNotNull()
        assertThat(repo.findLatest(player)!!.savedAt.toEpochMilli()).isEqualTo(version)
        val token = HandoffTokenCodec.decode(tokenFrom(source, game).payload)!!
        assertThat(token).isEqualTo(HandoffToken(player, version!!, lobby))

        source.inventory.items = List(9) { null } // the inventory left with the player
        source.service.onQuit(player)
        assertThat(repo.findLatest(player)!!.hotbar).isEqualTo(sword)
    }

    @Test
    fun `versions strictly increase even within one millisecond`() {
        val repo = InMemoryPlayerInventorySnapshotRepository()
        val source = Server(lobby, awaitToken = true, repo) { now }
        val first = source.service.onTransferRequested(player, game)!!
        val second = source.service.onTransferRequested(player, game)!!
        assertThat(second).isGreaterThan(first)
    }

    @Test
    fun `arrival waits for the token then restores and announces`() {
        val repo = InMemoryPlayerInventorySnapshotRepository()
        val source = Server(lobby, awaitToken = true, repo) { now }
        val target = Server(game, awaitToken = true, repo) { now }
        source.inventory.items = sword
        source.service.onTransferRequested(player, game)

        target.service.onJoin(player) // the player arrives before the token does
        assertThat(target.inventory.applied).isZero()
        assertThat(target.sessions.find(player)!!.state).isEqualTo(SessionState.Loading)

        target.messenger.deliver(tokenFrom(source, game))

        assertThat(target.inventory.items).isEqualTo(sword)
        assertThat(target.sessions.find(player)!!.state).isEqualTo(SessionState.Lobby)
        assertThat(target.arrivals.single().fromServer).isEqualTo(lobby)
    }

    @Test
    fun `a token that beat the player is used on join`() {
        val repo = InMemoryPlayerInventorySnapshotRepository()
        val source = Server(lobby, awaitToken = true, repo) { now }
        val target = Server(game, awaitToken = true, repo) { now }
        source.inventory.items = sword
        source.service.onTransferRequested(player, game)

        target.messenger.deliver(tokenFrom(source, game))
        target.service.onJoin(player)

        assertThat(target.inventory.items).isEqualTo(sword)
        assertThat(target.arrivals).hasSize(1)
    }

    @Test
    fun `a stale snapshot is not restored until the matching version is visible`() {
        val real = InMemoryPlayerInventorySnapshotRepository()
        val old = PlayerInventorySnapshot.empty(player).copy(savedAt = java.time.Instant.ofEpochMilli(10))
        real.save(old)
        var visible = false
        val lagging = object : IPlayerInventorySnapshotRepository {
            override fun save(snapshot: PlayerInventorySnapshot) = real.save(snapshot)
            override fun findLatest(playerId: PlayerId) = if (visible) real.findLatest(playerId) else old
            override fun delete(playerId: PlayerId) = real.delete(playerId)
        }
        val source = Server(lobby, awaitToken = true, real) { now }
        val target = Server(game, awaitToken = true, lagging) { now }
        source.inventory.items = sword
        source.service.onTransferRequested(player, game)

        target.service.onJoin(player)
        target.messenger.deliver(tokenFrom(source, game))
        assertThat(target.inventory.applied).`as`("stale snapshot must not be applied").isZero()

        target.scheduler.advanceTicks(10)
        assertThat(target.inventory.applied).isZero()
        visible = true
        target.scheduler.advanceTicks(4)

        assertThat(target.inventory.items).isEqualTo(sword)
        assertThat(target.arrivals).hasSize(1)
    }

    @Test
    fun `a handoff token waits for a snapshot that is not visible yet`() {
        val repo = InMemoryPlayerInventorySnapshotRepository()
        var visible = false
        val lagging = object : IPlayerInventorySnapshotRepository {
            override fun save(snapshot: PlayerInventorySnapshot) = repo.save(snapshot)
            override fun findLatest(playerId: PlayerId) = if (visible) repo.findLatest(playerId) else null
            override fun delete(playerId: PlayerId) = repo.delete(playerId)
        }
        val source = Server(lobby, awaitToken = true, repo) { now }
        val target = Server(game, awaitToken = true, lagging) { now }
        source.inventory.items = sword
        source.service.onTransferRequested(player, game)
        target.service.onJoin(player)
        target.messenger.deliver(tokenFrom(source, game))
        target.scheduler.advanceTicks(10)

        assertThat(target.arrivals).isEmpty()
        assertThat(target.sessions.find(player)!!.state).isEqualTo(SessionState.Loading)
        assertThat(target.inventory.applied).isZero()
        visible = true
        target.scheduler.advanceTicks(4)
        assertThat(target.inventory.items).isEqualTo(sword)
        assertThat(target.arrivals).hasSize(1)
    }

    @Test
    fun `an expired early token cannot complete a later join`() {
        val repo = InMemoryPlayerInventorySnapshotRepository()
        val source = Server(lobby, awaitToken = true, repo) { now }
        val target = Server(game, awaitToken = true, repo) { now }
        source.inventory.items = sword
        source.service.onTransferRequested(player, game)
        target.messenger.deliver(tokenFrom(source, game))
        target.scheduler.advanceTicks(601) // the token's 600-tick TTL has passed
        target.service.onJoin(player)

        assertThat(target.inventory.applied).isZero()
        assertThat(target.arrivals).isEmpty()
        assertThat(target.sessions.find(player)!!.state).isEqualTo(SessionState.Loading)
        now += 1000
        source.service.onTransferRequested(player, game)
        target.messenger.deliver(tokenFrom(source, game))
        assertThat(target.inventory.items).isEqualTo(sword)
        assertThat(target.arrivals).hasSize(1)
    }

    @Test
    fun `no token within the bound restores the latest snapshot anyway`() {
        val repo = InMemoryPlayerInventorySnapshotRepository()
        repo.save(PlayerInventorySnapshot.empty(player).copy(hotbar = sword))
        val target = Server(game, awaitToken = true, repo) { now }

        target.service.onJoin(player)
        target.scheduler.advanceTicks(39)
        assertThat(target.inventory.applied).isZero()
        target.scheduler.advanceTicks(3)

        assertThat(target.inventory.items).isEqualTo(sword)
        assertThat(target.arrivals.single().fromServer).isEqualTo(PlayerHandoffService.UNKNOWN_SERVER)
        assertThat(target.sessions.find(player)!!.state).isEqualTo(SessionState.Lobby)
    }

    @Test
    fun `a single server restores on join without waiting`() {
        val repo = InMemoryPlayerInventorySnapshotRepository()
        repo.save(PlayerInventorySnapshot.empty(player).copy(hotbar = sword))
        val only = Server(lobby, awaitToken = false, repo) { now }

        only.service.onJoin(player)

        assertThat(only.inventory.items).isEqualTo(sword)
    }

    @Test
    fun `quitting while still waiting never overwrites the stored snapshot`() {
        val repo = InMemoryPlayerInventorySnapshotRepository()
        repo.save(PlayerInventorySnapshot.empty(player).copy(hotbar = sword))
        val target = Server(game, awaitToken = true, repo) { now }

        target.service.onJoin(player)
        target.service.onQuit(player)
        target.scheduler.advanceTicks(60)

        assertThat(repo.findLatest(player)!!.hotbar).isEqualTo(sword)
        assertThat(target.inventory.applied).isZero()
        assertThat(target.arrivals).isEmpty()
    }

    @Test
    fun `a player held by a match keeps the pre-match snapshot on quit`() {
        val repo = InMemoryPlayerInventorySnapshotRepository()
        repo.save(PlayerInventorySnapshot.empty(player).copy(hotbar = sword))
        val game = Server(game, awaitToken = true, repo) { now }
        game.held = true
        game.inventory.items = List(9) { null }

        game.service.onQuit(player)

        assertThat(repo.findLatest(player)!!.hotbar).isEqualTo(sword)
    }
}
