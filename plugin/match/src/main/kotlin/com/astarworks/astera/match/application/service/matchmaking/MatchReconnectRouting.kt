package com.astarworks.astera.match.application.service.matchmaking

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IProxyMessenger
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IServerTransferGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.PlayerArrivedOnServer
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ProxyChannel
import com.astarworks.astera.core.domain.proxy.TransferReason
import com.astarworks.astera.match.domain.event.PlayerDisconnectedFromMatch
import com.astarworks.astera.match.domain.event.PlayerReconnectedToMatch
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Wire format of `ProxyChannel.MATCH_RECONNECT`: the game server's "this player may come back" hold / release. */
public sealed interface ReconnectNotice {
    public val playerId: PlayerId

    /** [playerId] dropped out of the running match and has [windowSeconds] to come back to it. */
    public data class Hold(override val playerId: PlayerId, val windowSeconds: Int) : ReconnectNotice

    /** The claim is gone (player is back, match over): a login must not be routed to the game server any more. */
    public data class Release(override val playerId: PlayerId) : ReconnectNotice
}

public object ReconnectNoticeCodec {
    private const val VERSION: Int = 1
    private const val HOLD: Int = 0
    private const val RELEASE: Int = 1

    public fun encode(notice: ReconnectNotice): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeInt(VERSION)
            it.writeUTF(notice.playerId.uuid.toString())
            when (notice) {
                is ReconnectNotice.Hold -> {
                    it.writeInt(HOLD)
                    it.writeInt(notice.windowSeconds)
                }
                is ReconnectNotice.Release -> it.writeInt(RELEASE)
            }
        }
        return out.toByteArray()
    }

    public fun decode(bytes: ByteArray): ReconnectNotice? = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use {
            require(it.readInt() == VERSION)
            val player = PlayerId(UUID.fromString(it.readUTF()))
            when (it.readInt()) {
                HOLD -> ReconnectNotice.Hold(player, it.readInt().also { s -> require(s in 1..MAX_WINDOW_SECONDS) })
                RELEASE -> ReconnectNotice.Release(player)
                else -> error("unknown reconnect notice")
            }
        }
    }.getOrNull()

    private const val MAX_WINDOW_SECONDS = 3600
}

/**
 * Game-server side of the mid-match reconnect route: tells the lobby which players dropped out of the
 * running match (and for how long they may come back), and withdraws the claim once it no longer applies.
 *
 * Velocity always lands a fresh login on the lobby; the lobby's [MatchReconnectRouter] uses these notices
 * to send the player on to this server, where `ReconnectDuringMatchListener` puts them back on their team.
 */
public class MatchReconnectAnnouncer(
    private val messenger: IProxyMessenger,
    private val matches: IMatchRepository,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
    private val settings: CrossServerSettings,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val handles = mutableListOf<() -> Unit>()

    public fun start() {
        val dropped = eventBus.subscribe(PlayerDisconnectedFromMatch::class) { event ->
            val timeoutAt = event.willTimeoutAtTick ?: return@subscribe // removed outright: nothing to come back to
            val seconds = ((timeoutAt - scheduler.currentTick()) / TICKS_PER_SECOND).toInt()
            if (seconds > 0) send(ReconnectNotice.Hold(event.playerId, seconds))
        }
        val back = eventBus.subscribe(PlayerReconnectedToMatch::class) { send(ReconnectNotice.Release(it.playerId)) }
        val ended = eventBus.subscribe(MatchEnded::class) { event ->
            matches.findById(event.matchId)?.teams?.flatMap { it.members }?.forEach { send(ReconnectNotice.Release(it)) }
        }
        handles += { dropped.cancel() }
        handles += { back.cancel() }
        handles += { ended.cancel() }
    }

    public fun stop() {
        handles.forEach { runCatching(it) }
        handles.clear()
    }

    private fun send(notice: ReconnectNotice) {
        val sent = messenger.sendMessage(settings.lobbyServer, ProxyChannel.MATCH_RECONNECT, ReconnectNoticeCodec.encode(notice))
        if (sent is Result.Failure) log.warn("Reconnect notice {} could not be sent to {}: {}", notice, settings.lobbyServer, sent.error)
    }

    private companion object {
        const val TICKS_PER_SECOND: Long = 20L
    }
}

/**
 * Lobby side of the mid-match reconnect route: remembers the players the game server holds a seat for and,
 * when one of them arrives on the lobby (after the inventory hand-off restore), sends them straight on to
 * the game server instead of leaving them in the lobby. A hold is used once and expires with the window.
 */
public class MatchReconnectRouter(
    private val messenger: IProxyMessenger,
    private val transfers: IServerTransferGateway,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
    private val settings: CrossServerSettings,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Player -> tick at which the hold lapses. */
    private val holds: MutableMap<PlayerId, Long> = ConcurrentHashMap()
    private val handles = mutableListOf<() -> Unit>()

    public fun start() {
        val notices = messenger.subscribe(ProxyChannel.MATCH_RECONNECT) { message ->
            when (val notice = ReconnectNoticeCodec.decode(message.payload)) {
                null -> log.warn("Dropping malformed reconnect notice from {}", message.fromServer)
                is ReconnectNotice.Hold -> holds[notice.playerId] = scheduler.currentTick() + notice.windowSeconds * TICKS_PER_SECOND
                is ReconnectNotice.Release -> holds.remove(notice.playerId)
            }
        }
        val arrived = eventBus.subscribe(PlayerArrivedOnServer::class) { route(it.playerId) }
        handles += { notices.cancel() }
        handles += { arrived.cancel() }
    }

    public fun stop() {
        handles.forEach { runCatching(it) }
        handles.clear()
        holds.clear()
    }

    private fun route(playerId: PlayerId) {
        val until = holds.remove(playerId) ?: return
        if (scheduler.currentTick() >= until) return
        log.info("{} has a seat in a running match on {}; sending them back.", playerId, settings.gameServer)
        val moved = transfers.transfer(playerId, settings.gameServer, TransferReason.MatchFound)
        if (moved is Result.Failure) log.warn("Reconnect transfer of {} to {} failed: {}", playerId, settings.gameServer, moved.error)
    }

    private companion object {
        const val TICKS_PER_SECOND: Long = 20L
    }
}
