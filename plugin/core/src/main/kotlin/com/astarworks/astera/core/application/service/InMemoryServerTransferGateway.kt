package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IProxyMessenger
import com.astarworks.astera.core.application.port.outbound.IServerTransferGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerTransferRequested
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ProxyError
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.TransferError
import com.astarworks.astera.core.domain.proxy.TransferReason
import com.astarworks.astera.core.domain.proxy.TransferWhen
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory [IServerTransferGateway] for Phase 1 (ADR-0084, Wave 5r).
 *
 * Combines:
 *  - [IProxyMessenger] (lowered to `sendToServer` for the actual move)
 *  - [IPermissionService] (Wave 5t role check: `astera.transfer.<server>`)
 *  - [IDomainEventBus] (emits [PlayerTransferRequested]; subscribes to
 *    [PlayerJoinedServer] / [MatchEnded] to fire deferred queues)
 *
 * Test hooks: [queueDepth] / [pendingFor] expose the deferred queue;
 * [flushAllImmediate] forces every queued [TransferWhen.Immediately] entry
 * to fire (the live gateway flushes after the tick).
 *
 * Permission policy:
 *  - When [permissionService] is `null`, the gateway is in "no-RBAC" mode
 *    (single-node dev) and never denies.
 *  - Otherwise the node is `astera.transfer.<server.value>`; absence of any
 *    role granting that node → [TransferError.PermissionDenied].
 *
 * Capacity: defaults to 1024 deferred entries to keep tests deterministic
 * without sweep logic; production overlays will scale. Hitting the cap
 * returns [TransferError.QueueFull].
 */
public class InMemoryServerTransferGateway(
    private val messenger: IProxyMessenger,
    private val eventBus: IDomainEventBus,
    private val permissionService: IPermissionService? = null,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) : IServerTransferGateway {

    private data class QueuedTransfer(
        val playerId: PlayerId,
        val targetServer: ServerName,
        val transferReason: TransferReason,
    )

    private val onLoginQueue: MutableMap<PlayerId, QueuedTransfer> = ConcurrentHashMap()
    private val onMatchEndQueue: MutableMap<MatchId, ConcurrentLinkedQueue<QueuedTransfer>> = ConcurrentHashMap()
    private val immediateQueue: ConcurrentLinkedQueue<QueuedTransfer> = ConcurrentLinkedQueue()
    private val depth: AtomicInteger = AtomicInteger(0)

    init {
        // Wire the two passive triggers. Subscriptions live for the lifetime
        // of the gateway (Phase 1 has no disable hook for non-plugin services).
        eventBus.subscribe(PlayerJoinedServer::class) { event -> fireOnLogin(event.playerId) }
        eventBus.subscribe(MatchEnded::class) { event -> fireOnMatchEnd(event.matchId) }
    }

    // ─── IServerTransferGateway ────────────────────────────────────────────

    override fun transfer(
        playerId: PlayerId,
        targetServer: ServerName,
        transferReason: TransferReason,
    ): Result<Unit, TransferError> {
        permissionService?.let { svc ->
            val node = permissionNodeFor(targetServer)
            if (!svc.check(playerId, Permission(node))) {
                return Result.failure(TransferError.PermissionDenied(node))
            }
        }

        // Pre-publish the domain event so HUD / audit / inventory snapshot
        // listeners can react before the proxy hop actually starts.
        eventBus.publish(
            PlayerTransferRequested(
                playerId = playerId,
                targetServer = targetServer,
                transferReason = transferReason,
            ),
        )

        return when (val sent = messenger.sendToServer(playerId, targetServer)) {
            is Result.Success -> Result.success(Unit)
            is Result.Failure -> Result.failure(wrap(sent.error))
        }
    }

    override fun queueTransfer(
        playerId: PlayerId,
        targetServer: ServerName,
        when_: TransferWhen,
        transferReason: TransferReason,
    ): Result<Unit, TransferError> {
        if (depth.get() >= queueCapacity) {
            return Result.failure(TransferError.QueueFull)
        }
        val entry = QueuedTransfer(playerId, targetServer, transferReason)
        when (when_) {
            is TransferWhen.Immediately -> {
                immediateQueue.add(entry)
                depth.incrementAndGet()
            }
            is TransferWhen.OnNextLogin -> {
                onLoginQueue[playerId] = entry
                depth.incrementAndGet()
            }
            is TransferWhen.AfterCurrentMatch -> {
                onMatchEndQueue
                    .computeIfAbsent(when_.matchId) { ConcurrentLinkedQueue() }
                    .add(entry)
                depth.incrementAndGet()
            }
        }
        return Result.success(Unit)
    }

    // ─── Test / adapter hooks ──────────────────────────────────────────────

    /** Total pending entries across all three queues. */
    public fun queueDepth(): Int = depth.get()

    /** Pending on-login transfer for [playerId], if any. */
    public fun pendingFor(playerId: PlayerId): QueuedTransferView? =
        onLoginQueue[playerId]?.let { QueuedTransferView(it.playerId, it.targetServer, it.transferReason) }

    /**
     * Fire every [TransferWhen.Immediately] entry now. Returns the per-entry
     * result list in queue order. The gateway re-checks permission on each
     * entry (queueing does not bypass the role gate).
     */
    public fun flushAllImmediate(): List<Result<Unit, TransferError>> {
        val results = mutableListOf<Result<Unit, TransferError>>()
        while (true) {
            val entry = immediateQueue.poll() ?: break
            depth.decrementAndGet()
            results.add(transfer(entry.playerId, entry.targetServer, entry.transferReason))
        }
        return results
    }

    // ─── Internals ─────────────────────────────────────────────────────────

    private fun fireOnLogin(playerId: PlayerId) {
        val entry = onLoginQueue.remove(playerId) ?: return
        depth.decrementAndGet()
        transfer(entry.playerId, entry.targetServer, entry.transferReason)
    }

    private fun fireOnMatchEnd(matchId: MatchId) {
        val queue = onMatchEndQueue.remove(matchId) ?: return
        while (true) {
            val entry = queue.poll() ?: break
            depth.decrementAndGet()
            transfer(entry.playerId, entry.targetServer, entry.transferReason)
        }
    }

    private fun wrap(error: ProxyError): TransferError = when (error) {
        is ProxyError.UnknownTargetServer -> TransferError.TargetServerOffline(error.server)
        else -> TransferError.ProxyUnavailable(error)
    }

    /**
     * Read-only projection of [QueuedTransfer] for tests / adapters. Kept
     * separate so [QueuedTransfer]'s internal layout can evolve.
     */
    public data class QueuedTransferView(
        val playerId: PlayerId,
        val targetServer: ServerName,
        val transferReason: TransferReason,
    )

    public companion object {
        public const val DEFAULT_QUEUE_CAPACITY: Int = 1024

        /** Permission node guarding transfers to [targetServer]. */
        public fun permissionNodeFor(targetServer: ServerName): String =
            "astera.transfer.${targetServer.value}"
    }
}
